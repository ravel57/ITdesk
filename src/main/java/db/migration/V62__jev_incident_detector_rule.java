package db.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;


/**
 * Правило «Детектор массовых инцидентов (Jev)» раньше было одним непрозрачным действием
 * {@code jev.detectIncident()}: окно, порог числа клиентов, порог «это сбой», пауза и режим (уведомить или
 * создать черновик) хранились в отдельных настройках Jev, а остальное — серьёзность, смягчение порога при
 * тревоге мониторинга, интервалы обновлений — было зашито в Java. Теперь это обычные condition/action-ноды:
 * пользователь меняет числа, добавляет условия (свои пороги и окна для отдельных организаций, другая схема
 * при срабатывании) прямо в редакторе правила.
 * <p>
 * Текущие значения настроек переносятся в ноды правила, чтобы поведение не изменилось, после чего колонки
 * настроек детектора удаляются.
 * <p>
 * Правило собирается в {@link #workflow(Params)}; метод статический, чтобы тесты могли прогнать то же правило.
 */
public class V62__jev_incident_detector_rule extends BaseJavaMigration {

	public static final String RULE_NAME = "Детектор массовых инцидентов (Jev)";

	static final String DESCRIPTION = "Jev размечает входящие сообщения, правило считает похожие сбои от разных клиентов в окне "
			+ "и при достижении порога уведомляет ответственных или создаёт черновик инцидента. Окно, число клиентов, "
			+ "порог «это сбой», паузу, серьёзность и схему срабатывания меняйте нодами этого правила; для отдельных "
			+ "организаций добавьте условие (например client.organization.id = 5) со своим incident.countSignals(...).";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * @param windowMinutes        окно подсчёта обращений, минут
	 * @param minClients           сколько разных клиентов должно обратиться в окне
	 * @param outageThreshold      вероятность «это сбой» не ниже
	 * @param cooldownMinutes      пауза после срабатывания, минут (0 — без паузы)
	 * @param createDraft          при срабатывании создавать черновик инцидента (иначе только уведомлять)
	 * @param p1MinClients         P1 только при стольких клиентах, иначе не выше P2
	 * @param sameProblemThreshold вероятность «одна проблема в пачке» не ниже
	 */
	public record Params(
			int windowMinutes,
			int minClients,
			double outageThreshold,
			int cooldownMinutes,
			boolean createDraft,
			int p1MinClients,
			double sameProblemThreshold
	) {
		public static Params defaults() {
			return new Params(15, 3, 0.7, 60, false, 10, 0.6);
		}
	}


	@Override
	public void migrate(Context context) throws Exception {
		Connection connection = context.getConnection();
		Params params = readParams(connection);
		try (PreparedStatement update = connection.prepareStatement(
				"update automation_trigger set description = ?, workflow_definition = ? where name = ?")) {
			update.setString(1, DESCRIPTION);
			update.setString(2, workflow(params));
			update.setString(3, RULE_NAME);
			update.executeUpdate();
		}
		try (Statement drop = connection.createStatement()) {
			for (String column : List.of(
					"jev_incident_window_minutes", "jev_incident_min_clients", "jev_incident_outage_threshold",
					"jev_incident_cooldown_minutes", "jev_incident_mode", "jev_incident_p1_min_clients")) {
				drop.execute("alter table app_settings drop column if exists " + column);
			}
		}
	}


	private static Params readParams(Connection connection) throws Exception {
		Params defaults = Params.defaults();
		try (Statement statement = connection.createStatement();
		     ResultSet row = statement.executeQuery(
				     "select jev_incident_window_minutes, jev_incident_min_clients, jev_incident_outage_threshold, "
						     + "jev_incident_cooldown_minutes, jev_incident_mode, jev_incident_p1_min_clients, "
						     + "cast(jev_thresholds as text) from app_settings order by id limit 1")) {
			if (!row.next()) {
				return defaults;
			}
			double sameProblem = defaults.sameProblemThreshold();
			String thresholds = row.getString(7);
			if (thresholds != null && !thresholds.isBlank()) {
				JsonNode stored = MAPPER.readTree(thresholds).path("detectIncident");
				if (stored.isNumber()) {
					sameProblem = stored.asDouble();
				}
			}
			return new Params(
					row.getInt(1),
					row.getInt(2),
					row.getDouble(3),
					row.getInt(4),
					"CREATE_DRAFT".equals(row.getString(5)),
					row.getInt(6),
					sameProblem
			);
		}
	}


	/**
	 * Граф правила: разметка Jev → ветка «Jev сопоставил с открытым инцидентом» → «это сбой» → подсчёт окна и порог
	 * → пауза → проверка пачки → серьёзность → срабатывание. Каждое решение по сигналу записывается
	 * ({@code incident.markSignal}), чтобы подсчёт окна и пауза видели прошлые обращения.
	 */
	public static String workflow(Params p) {
		String window = String.valueOf(p.windowMinutes());
		String minClientsAlert = String.valueOf(Math.max(2, p.minClients() - 1));
		String attachMatched = p.createDraft()
				? "incident.attachSignal(var.matchedIncidentId, " + window + ")"
				: "incident.markSignal('MATCHED_INCIDENT', var.matchedIncidentId)";
		String attachCooldown = p.createDraft()
				? "incident.attachSignal(var.cooldownIncidentId, " + window + ")"
				: "incident.markSignal('MATCHED_INCIDENT', var.cooldownIncidentId)";
		String summary = "'Массовые обращения: ' + var.outageServiceName + ' — ' + var.outageSymptom + ': ' + var.signalClients"
				+ " + ' клиент(ов) за ' + var.signalWindowMinutes + ' мин, серьёзность ' + var.severity"
				+ " + ', вероятная причина — ' + var.batchCause";
		String trigger = p.createDraft()
				? "incident.createDraftFromSignals(var.severity, 15, 30, 60, 120); "
				+ "incident.notifyResponsible('Создан черновик инцидента ' + var.draftIncidentNumber + '. ' + " + summary
				+ " + '. Проверьте и объявите инцидент.')"
				: "incident.markSignal('TRIGGERED'); "
				+ "incident.notifyResponsible('Обнаружен массовый сбой. ' + " + summary
				+ " + '. Инцидент не создан (в правиле выбрано «только уведомлять»).')";

		ArrayNode nodes = MAPPER.createArrayNode();
		ArrayNode edges = MAPPER.createArrayNode();

		nodes.add(node("inc-event", "EVENT", "Событие", 80, 300, config("triggerType", "MESSAGE_INCOMING")));
		nodes.add(action("inc-analyze", "Jev: разметка сообщения", 380, 300, "jev.analyzeOutage()"));
		nodes.add(condition("inc-c-answer", "Jev ответил?", 700, 300, "var.signalId.notNull()"));
		nodes.add(condition("inc-c-matched", "Относится к открытому инциденту?", 1020, 300, "var.matchedIncidentId.notNull()"));
		nodes.add(action("inc-attach-matched", "Привязать к инциденту", 1340, 60, attachMatched));
		nodes.add(condition("inc-c-outage", "Это сбой с понятным сервисом?", 1340, 300,
				"var.outageServiceId.notNull() && var.outageProbability >= " + p.outageThreshold()));
		nodes.add(action("inc-ignore", "Не считать", 1660, 540, "incident.markSignal('IGNORED')"));
		nodes.add(action("inc-count", "Посчитать обращения в окне", 1660, 300, "incident.countSignals(" + window + ")"));
		nodes.add(condition("inc-c-threshold", "Достаточно клиентов?", 1980, 300,
				"var.signalClients >= " + p.minClients() + " || ((var.monitoringStatus = 'WARNING' || var.monitoringStatus = 'CRITICAL')"
						+ " && var.signalClients >= " + minClientsAlert + ")"));
		nodes.add(action("inc-counted", "Учесть, порог не достигнут", 2300, 540, "incident.markSignal('COUNTED')"));
		nodes.add(action("inc-cooldown", "Проверить паузу после срабатывания", 2300, 300,
				"incident.checkCooldown(" + p.cooldownMinutes() + ", " + p.createDraft() + ")"));
		nodes.add(condition("inc-c-recent-incident", "Недавний инцидент по этой проблеме?", 2620, 300, "var.cooldownIncidentId.notNull()"));
		nodes.add(action("inc-attach-recent", "Привязать к недавнему инциденту", 2940, 60, attachCooldown));
		nodes.add(condition("inc-c-cooldown", "Пауза ещё идёт?", 2940, 300, "var.cooldownActive"));
		nodes.add(action("inc-in-cooldown", "Пауза", 3260, 540, "incident.markSignal('COOLDOWN')"));
		nodes.add(action("inc-verify", "Jev: проверка пачки", 3260, 300, "jev.verifyBatch()"));
		nodes.add(condition("inc-c-verified", "Проверка выполнена?", 3580, 300, "var.batchSameProblem.notNull()"));
		nodes.add(action("inc-unverified", "Учесть без проверки", 3580, 540, "incident.markSignal('COUNTED')"));
		nodes.add(condition("inc-c-same", "Одна проблема?", 3900, 300, "var.batchSameProblem >= " + p.sameProblemThreshold()));
		nodes.add(action("inc-rejected", "Разные проблемы", 3900, 540, "incident.markSignal('BATCH_REJECTED')"));
		nodes.add(setVariable("inc-severity", "Серьёзность по оценке Jev", 4220, 300, "severity", "var.batchSeverity"));
		nodes.add(condition("inc-c-p1", "P1 при малом числе клиентов?", 4540, 300,
				"var.batchSeverity = 'P1' && var.signalClients < " + p.p1MinClients()));
		nodes.add(setVariable("inc-cap", "Не выше P2", 4540, 60, "severity", "'P2'"));
		nodes.add(action("inc-trigger", p.createDraft() ? "Создать черновик и уведомить" : "Уведомить ответственных",
				4860, 300, trigger));
		nodes.add(node("inc-end", "END", "Завершение", 5180, 300, config("result", "Сценарий завершён")));

		edge(edges, "inc-event", "inc-analyze", "next");
		edge(edges, "inc-analyze", "inc-c-answer", "next");
		edge(edges, "inc-c-answer", "inc-c-matched", "true");
		edge(edges, "inc-c-answer", "inc-end", "false");
		edge(edges, "inc-c-matched", "inc-attach-matched", "true");
		edge(edges, "inc-c-matched", "inc-c-outage", "false");
		edge(edges, "inc-attach-matched", "inc-end", "next");
		edge(edges, "inc-c-outage", "inc-count", "true");
		edge(edges, "inc-c-outage", "inc-ignore", "false");
		edge(edges, "inc-ignore", "inc-end", "next");
		edge(edges, "inc-count", "inc-c-threshold", "next");
		edge(edges, "inc-c-threshold", "inc-cooldown", "true");
		edge(edges, "inc-c-threshold", "inc-counted", "false");
		edge(edges, "inc-counted", "inc-end", "next");
		edge(edges, "inc-cooldown", "inc-c-recent-incident", "next");
		edge(edges, "inc-c-recent-incident", "inc-attach-recent", "true");
		edge(edges, "inc-c-recent-incident", "inc-c-cooldown", "false");
		edge(edges, "inc-attach-recent", "inc-end", "next");
		edge(edges, "inc-c-cooldown", "inc-in-cooldown", "true");
		edge(edges, "inc-c-cooldown", "inc-verify", "false");
		edge(edges, "inc-in-cooldown", "inc-end", "next");
		edge(edges, "inc-verify", "inc-c-verified", "next");
		edge(edges, "inc-c-verified", "inc-c-same", "true");
		edge(edges, "inc-c-verified", "inc-unverified", "false");
		edge(edges, "inc-unverified", "inc-end", "next");
		edge(edges, "inc-c-same", "inc-severity", "true");
		edge(edges, "inc-c-same", "inc-rejected", "false");
		edge(edges, "inc-rejected", "inc-end", "next");
		edge(edges, "inc-severity", "inc-c-p1", "next");
		edge(edges, "inc-c-p1", "inc-cap", "true");
		edge(edges, "inc-c-p1", "inc-trigger", "false");
		edge(edges, "inc-cap", "inc-trigger", "next");
		edge(edges, "inc-trigger", "inc-end", "next");

		ObjectNode workflow = MAPPER.createObjectNode();
		workflow.put("version", 2);
		workflow.put("entryNodeId", "inc-event");
		workflow.set("nodes", nodes);
		workflow.set("edges", edges);
		return workflow.toString();
	}


	private static ObjectNode node(String id, String type, String label, double x, double y, ObjectNode config) {
		ObjectNode node = MAPPER.createObjectNode();
		node.put("id", id);
		node.put("type", type);
		node.put("label", label);
		node.put("x", x);
		node.put("y", y);
		node.set("config", config);
		return node;
	}


	private static ObjectNode config(String key, String value) {
		return MAPPER.createObjectNode().put(key, value);
	}


	private static ObjectNode action(String id, String label, double x, double y, String script) {
		ObjectNode config = MAPPER.createObjectNode();
		config.put("actionScript", script);
		config.putArray("actions");
		config.put("continueOnError", false);
		config.put("manualScript", true);
		return node(id, "ACTION", label, x, y, config);
	}


	private static ObjectNode condition(String id, String label, double x, double y, String expression) {
		ObjectNode config = MAPPER.createObjectNode();
		config.put("expression", expression);
		config.putArray("groups");
		config.put("manualExpression", true);
		return node(id, "CONDITION", label, x, y, config);
	}


	private static ObjectNode setVariable(String id, String label, double x, double y, String name, String expression) {
		ObjectNode config = MAPPER.createObjectNode();
		config.put("name", name);
		config.put("expression", expression);
		return node(id, "SET_VARIABLE", label, x, y, config);
	}


	private static void edge(ArrayNode edges, String source, String target, String handle) {
		ObjectNode edge = MAPPER.createObjectNode();
		edge.put("id", "edge-%d".formatted(edges.size() + 1));
		edge.put("source", source);
		edge.put("target", target);
		edge.put("sourceHandle", handle);
		edges.add(edge);
	}

}

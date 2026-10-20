package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Прогоняет граф правила из SQL-миграции так же, как движок автоматизаций: CONDITION —
 * {@link AutomationScriptRuntime#evaluateExpression}, ACTION — {@link AutomationScriptRuntime#executeActions},
 * SET_VARIABLE — {@link AutomationWorkflowValueService}. Шаги ACTION — настоящий {@link AutomationActionExecutor}.
 */
final class WorkflowRuleRunner {

	private final ObjectMapper mapper = new ObjectMapper();
	private final AutomationScriptRuntime runtime;
	private final AutomationWorkflowValueService values;

	WorkflowRuleRunner(AutomationScriptRuntime runtime, AutomationWorkflowValueService values) {
		this.runtime = runtime;
		this.values = values;
	}

	/** Граф правила из SQL-миграции: JSON между $json$ … $json$ (комментарии миграции отбрасываются). */
	static String workflowOf(String migration) {
		String sql = withoutComments(migration);
		Matcher matcher = Pattern.compile("\\$json\\$(.*?)\\$json\\$", Pattern.DOTALL).matcher(sql);
		assertTrue(matcher.find(), "no $json$ block in " + migration);
		return matcher.group(1).trim();
	}

	static String descriptionOf(String migration) {
		Matcher matcher = Pattern.compile("set description\\s*=\\s*'(.*?)',", Pattern.DOTALL).matcher(withoutComments(migration));
		assertTrue(matcher.find(), "no description in " + migration);
		return matcher.group(1);
	}

	private static String withoutComments(String migration) {
		try (InputStream stream = WorkflowRuleRunner.class.getResourceAsStream("/db/migration/" + migration)) {
			assertTrue(stream != null, "migration not found: " + migration);
			String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
			return Arrays.stream(sql.split("\n")).filter(line -> !line.trim().startsWith("--"))
					.collect(java.util.stream.Collectors.joining("\n"));
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Правка графа, как если бы её внёс пользователь в редакторе: подстрока обязана существовать. */
	static String edit(String workflow, String from, String to) {
		assertTrue(workflow.contains(from), "rule has no «" + from + "»");
		return workflow.replace(from, to);
	}

	/** Исполнитель действий с настоящими перечисленными зависимостями, остальные — заглушки. */
	static AutomationActionExecutor executor(Map<Class<?>, Object> real) throws Exception {
		Constructor<?> constructor = AutomationActionExecutor.class.getDeclaredConstructors()[0];
		Object[] args = Arrays.stream(constructor.getParameterTypes())
				.map(type -> real.containsKey(type) ? real.get(type) : mock(type))
				.toArray();
		return (AutomationActionExecutor) constructor.newInstance(args);
	}

	/** Проход графа для события; возвращает payload, в котором лежат переменные правила. */
	ObjectNode run(String workflow, TriggerType trigger, String ruleName, ObjectNode payload) throws Exception {
		JsonNode rule = mapper.readTree(workflow);
		Map<String, JsonNode> nodes = new HashMap<>();
		rule.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
		Map<String, String> edges = new HashMap<>();
		rule.path("edges").forEach(edge -> edges.put(edge.path("source").asText() + "/" + edge.path("sourceHandle").asText(),
				edge.path("target").asText()));

		Event event = Event.builder().triggerType(trigger).payload(payload).build();
		AutomationExecutionContext ctx = new AutomationExecutionContext(
				AutomationTrigger.builder().id(1L).name(ruleName).build(), event);

		String id = rule.path("entryNodeId").asText();
		for (int step = 0; id != null && step < 200; step++) {
			JsonNode node = nodes.get(id);
			JsonNode config = node.path("config");
			switch (node.path("type").asText()) {
				case "ACTION" -> {
					runtime.executeActions(config.path("actionScript").asText(), ctx);
					id = edges.get(id + "/next");
				}
				case "CONDITION" -> id = edges.get(id + "/" + runtime.evaluateExpression(config.path("expression").asText(), payload));
				case "SET_VARIABLE" -> {
					values.setVariable(payload, config.path("name").asText(), values.resolve(payload, config.path("expression").asText()));
					id = edges.get(id + "/next");
				}
				case "END" -> id = null;
				default -> id = edges.get(id + "/next");
			}
		}
		assertNull(id, "rule did not finish");
		return payload;
	}
}

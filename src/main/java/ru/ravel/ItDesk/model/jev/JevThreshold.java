package ru.ravel.ItDesk.model.jev;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;


/**
 * Пороги уверенности Jev по автоматизациям. Решение Jev применяется, только если его уверенность не ниже порога.
 * key хранится в app_settings.jev_thresholds и приходит из UI.
 */
public enum JevThreshold {

	CLASSIFY_TASK("classifyTask", "Классификация заявки",
			"jev.classifyTask(): тип, сервис и линия заявки", 0.6),
	SET_PRIORITY("setPriority", "Приоритет",
			"jev.setPriority(): приоритет заявки", 0.6),
	CREATE_TASK("createTaskIfNeeded", "Создание заявки",
			"jev.createTaskIfNeeded(): тип сообщения, совпадение с открытой заявкой, повтор закрытой", 0.6),
	SPLIT_TASKS("splitTasks", "Разбиение на несколько заявок",
			"jev.createTaskIfNeeded(): проблема по каждому сервису каталога. Выше — меньше лишних заявок", 0.75),
	KNOWLEDGE_ARTICLE("suggestKnowledgeArticle", "Статья базы знаний",
			"jev.suggestKnowledgeArticle(): подходящая статья", 0.6),
	INCIDENT_RECOVERY("checkIncidentRecovery", "Восстановление после инцидента",
			"jev.checkIncidentRecovery(): что клиент сообщает после восстановления", 0.6),
	SUPPORT_LINE("selectSupportLine", "Выбор линии",
			"jev.selectSupportLine(): линия поддержки для заявки", 0.6),
	ESCALATION("escalateIfNeeded", "Эскалация",
			"jev.escalateIfNeeded(): нужно ли передавать заявку и какой линии", 0.6),
	DUPLICATE("findDuplicate", "Дубли заявок",
			"jev.findDuplicate(): новая заявка о той же проблеме, что и открытая", 0.6),
	INCIDENT_ANALYSIS("detectIncident", "Детектор инцидентов: разбор",
			"jev.detectIncident(): сервис, симптом, связь с открытым инцидентом, одна ли проблема в пачке, причина", 0.6);

	private final String key;
	private final String label;
	private final String hint;
	private final double defaultValue;


	JevThreshold(String key, String label, String hint, double defaultValue) {
		this.key = key;
		this.label = label;
		this.hint = hint;
		this.defaultValue = defaultValue;
	}


	public String key() {
		return key;
	}


	public String label() {
		return label;
	}


	public String hint() {
		return hint;
	}


	public double defaultValue() {
		return defaultValue;
	}


	public static Optional<JevThreshold> byKey(String key) {
		return Arrays.stream(values()).filter(item -> item.key.equals(key)).findFirst();
	}


	/**
	 * Пороги из хранимой карты key → значение; отсутствующие берутся по умолчанию.
	 */
	public static Map<JevThreshold, Double> resolve(Map<String, Double> stored) {
		Map<JevThreshold, Double> result = new EnumMap<>(JevThreshold.class);
		for (JevThreshold item : values()) {
			Double value = stored == null ? null : stored.get(item.key);
			result.put(item, value == null ? item.defaultValue : value);
		}
		return result;
	}


	/**
	 * Одинаковый порог для всех автоматизаций.
	 */
	public static Map<JevThreshold, Double> uniform(double value) {
		Map<JevThreshold, Double> result = new EnumMap<>(JevThreshold.class);
		for (JevThreshold item : values()) {
			result.put(item, value);
		}
		return result;
	}

}

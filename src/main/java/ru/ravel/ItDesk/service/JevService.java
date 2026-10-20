package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.TaskType;
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.model.jev.JevThreshold;
import ru.ravel.ItDesk.repository.PriorityRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.SupportLineRepository;
import ru.ravel.ItDesk.repository.TaskTypeRepository;

import java.util.*;


@Service
@RequiredArgsConstructor
public class JevService {

	private static final Logger log = LoggerFactory.getLogger("JEV");
	private static final String NONE = "__none__";

	private final JevClient jevClient;
	private final TaskTypeRepository taskTypeRepository;
	private final ServiceRepository serviceRepository;
	private final SupportLineRepository supportLineRepository;
	private final PriorityRepository priorityRepository;
	private final ServiceCatalogService serviceCatalogService;

	private final JevSettingsService settingsService;
	private final JevDecisionRecorder decisionRecorder;


	public record Triage(
			TaskType type,
			ru.ravel.ItDesk.model.Service service,
			SupportLine supportLine
	) {
		public boolean isEmpty() {
			return type == null && service == null && supportLine == null;
		}
	}


	/** Симптомы для группировки обращений: ключ уходит в Jev и в название инцидента. */
	public static final Map<String, String> SYMPTOMS = Map.of(
			"недоступен", "Сервис или система не открывается, не отвечает, недоступна",
			"медленно", "Работает, но очень медленно, зависает, долго грузится",
			"вход", "Не получается войти, ошибка авторизации, пароль или учётная запись не принимаются",
			"ошибка", "Сервис открывается, но при выполнении операции появляется ошибка",
			"данные", "Неверные, пропавшие или не обновляющиеся данные",
			"другое", "Другая проблема"
	);

	/** Уровни score для severity: индекс 0 — P4 ... 3 — P1. */
	private static final List<String> SEVERITY_LEVELS = List.of(
			"P4: неудобство, есть обходной путь, затронуты единицы пользователей",
			"P3: функция работает с ограничениями у части пользователей",
			"P2: серьёзная деградация, многие пользователи не могут работать",
			"P1: сервис полностью недоступен, остановлена работа"
	);


	/**
	 * Разметка одного сообщения для детектора массовых инцидентов.
	 */
	public record OutageSignal(
			double outageProbability,
			ru.ravel.ItDesk.model.Service service,
			String symptom,
			SupportIncident matchedIncident,
			Double impactScore
	) {
	}


	/** Вероятные причины для черновика инцидента: ключ — короткое название, значение — пояснение для Jev. */
	public static final Map<String, String> PROBABLE_CAUSES = Map.of(
			"сеть", "Сеть, интернет-канал или провайдер связи",
			"авторизация", "Учётные записи, пароли, доменная авторизация, сертификаты",
			"обновление", "Недавнее обновление, изменение конфигурации или релиз",
			"оборудование", "Сервер, хранилище, рабочие станции или периферия",
			"внешний поставщик", "Облачный сервис или внешний поставщик",
			"неизвестно", "Причину по сообщениям определить нельзя"
	);


	/**
	 * Проверка пачки похожих сообщений перед срабатыванием.
	 *
	 * @param severity      серьёзность по оценке Jev, округлённая вниз (без ограничений по числу клиентов)
	 * @param severityScore сырая оценка Jev: 0 — P4 ... 3 — P1
	 * @param probableCause ключ из {@link #PROBABLE_CAUSES}
	 */
	public record BatchVerdict(
			double sameProblemProbability,
			IncidentSeverity severity,
			double severityScore,
			String probableCause
	) {
		public BatchVerdict(double sameProblemProbability, IncidentSeverity severity) {
			this(sameProblemProbability, severity, 3 - severity.ordinal(), "неизвестно");
		}
	}


	public enum ReplyDecision {
		REQUIRED,
		NOT_REQUIRED,
		UNDECIDED
	}


	public boolean isEnabled() {
		return jevClient.isEnabled();
	}


	// ------------------------------------------------------------------
	// 1. Классификация: тип заявки, сервис, линия поддержки
	// ------------------------------------------------------------------

	public Optional<Triage> classify(String context, String text, Organization organization) {
		if (!jevClient.isEnabled()) {
			return Optional.empty();
		}
		if (isBlank(text)) {
			log.debug("[{}] classify skipped: empty text", context);
			return Optional.empty();
		}
		List<TaskType> types = taskTypeRepository.findAll().stream().sorted().toList();
		List<ru.ravel.ItDesk.model.Service> services = availableServices(organization);
		List<SupportLine> lines = supportLineRepository.findAll().stream()
				.filter(line -> !Boolean.FALSE.equals(line.getActive()))
				.sorted()
				.toList();

		Map<String, TaskType> typeByKey = new LinkedHashMap<>();
		Map<String, Object> typeCriteria = new LinkedHashMap<>();
		for (TaskType type : types) {
			if (!isBlank(type.getType())) {
				typeByKey.put(type.getType(), type);
				typeCriteria.put(type.getType(), null);
			}
		}
		Map<String, ru.ravel.ItDesk.model.Service> serviceByKey = new LinkedHashMap<>();
		Map<String, Object> serviceCriteria = new LinkedHashMap<>();
		for (ru.ravel.ItDesk.model.Service service : services) {
			serviceByKey.put(service.getCode(), service);
			serviceCriteria.put(service.getCode(), describe(service.getName(), service.getDescription()));
		}
		Map<String, SupportLine> lineByKey = new LinkedHashMap<>();
		Map<String, Object> lineCriteria = new LinkedHashMap<>();
		for (SupportLine line : lines) {
			lineByKey.put(line.getName(), line);
			lineCriteria.put(line.getName(), describe(null, line.getDescription()));
		}

		Map<String, Object> questions = new LinkedHashMap<>();
		if (typeCriteria.size() >= 2) {
			questions.put("task_type", choice("Какого типа это обращение в службу поддержки?", typeCriteria));
		}
		if (!serviceCriteria.isEmpty()) {
			serviceCriteria.put(NONE, "Ни один из перечисленных сервисов не подходит или это неясно");
			questions.put("service", choice("К какому ИТ-сервису относится обращение?", serviceCriteria));
		}
		if (!lineCriteria.isEmpty()) {
			lineCriteria.put(NONE, "Ни одна из линий явно не подходит или это неясно");
			questions.put("support_line", choice("Какая линия поддержки должна обработать обращение?", lineCriteria));
		}
		if (questions.isEmpty()) {
			log.debug("[{}] classify skipped: nothing to choose from (types={}, services={}, lines={})",
					context, typeCriteria.size(), services.size(), lines.size());
			return Optional.empty();
		}

		double minConfidence = settingsService.effective().threshold(JevThreshold.CLASSIFY_TASK);
		return jevClient.evaluate(context, text, questions).map(answers -> new Triage(
				pick(context, answers, "task_type", typeByKey, minConfidence),
				pick(context, answers, "service", serviceByKey, minConfidence),
				pick(context, answers, "support_line", lineByKey, minConfidence)
		)).filter(triage -> !triage.isEmpty());
	}


	private List<ru.ravel.ItDesk.model.Service> availableServices(Organization organization) {
		// Не через resolveService: он @Transactional и бросает исключение для недоступного сервиса,
		// что помечает транзакцию автоматизации rollback-only даже при перехвате исключения.
		Set<Long> availableIds = new HashSet<>();
		for (Map<String, Object> service : serviceCatalogService.getAvailableServices(
				organization == null ? null : organization.getId())) {
			availableIds.add(((Number) service.get("id")).longValue());
		}
		return serviceRepository.findAllByActiveTrueOrderByOrderNumberAscNameAsc().stream()
				.filter(service -> availableIds.contains(service.getId()))
				.toList();
	}


	// ------------------------------------------------------------------
	// 2. Приоритет по тексту обращения
	// ------------------------------------------------------------------

	public Optional<Priority> suggestPriority(String context, String text) {
		if (!jevClient.isEnabled()) {
			return Optional.empty();
		}
		if (isBlank(text)) {
			log.debug("[{}] priority skipped: empty text", context);
			return Optional.empty();
		}
		List<String> excludedPriorities = settingsService.effective().priorityExcluded();
		List<Priority> priorities = priorityRepository.findAll().stream()
				.filter(priority -> !isBlank(priority.getName()))
				.filter(priority -> excludedPriorities.stream().noneMatch(name -> name.equalsIgnoreCase(priority.getName().trim())))
				.sorted()
				.toList();
		if (priorities.size() < 2) {
			log.debug("[{}] priority skipped: less than 2 priorities available (excluded={})", context, excludedPriorities);
			return Optional.empty();
		}
		Map<String, Priority> byKey = new LinkedHashMap<>();
		Map<String, Object> criteria = new LinkedHashMap<>();
		for (int i = 0; i < priorities.size(); i++) {
			Priority priority = priorities.get(i);
			byKey.put(priority.getName(), priority);
			String level = "уровень %d из %d по срочности (1 — самый срочный)".formatted(i + 1, priorities.size());
			criteria.put(priority.getName(), Boolean.TRUE.equals(priority.getCritical())
					? level + "; критичный: сервис недоступен или остановлена работа"
					: level);
		}
		Map<String, Object> questions = Map.of("priority", choice(
				"Какой приоритет следует назначить обращению с учётом срочности и влияния на работу пользователей?",
				criteria
		));
		double minConfidence = settingsService.effective().threshold(JevThreshold.SET_PRIORITY);
		return jevClient.evaluate(context, text, questions)
				.map(answers -> pick(context, answers, "priority", byKey, minConfidence));
	}


	// ------------------------------------------------------------------
	// 6. Нужен ли ответ оператора на сообщение клиента
	// ------------------------------------------------------------------

	public ReplyDecision needsReply(String context, String text) {
		if (!jevClient.isEnabled()) {
			return ReplyDecision.UNDECIDED;
		}
		if (isBlank(text)) {
			log.debug("[{}] needs_reply skipped: empty text", context);
			return ReplyDecision.UNDECIDED;
		}
		Map<String, Object> criteria = new LinkedHashMap<>();
		criteria.put("true", "Клиент задаёт вопрос, сообщает о проблеме или ждёт действий от поддержки");
		criteria.put("false", "Благодарность, подтверждение, прощание, эмодзи или другое сообщение, на которое отвечать не нужно");
		Map<String, Object> question = new LinkedHashMap<>();
		question.put("type", "noul");
		question.put("instructions", "Нужен ли ответ оператора поддержки на это сообщение клиента?");
		question.put("criteria", criteria);

		JevSettingsService.JevConfig config = settingsService.effective();
		return jevClient.evaluate(context, text, Map.of("needs_reply", question))
				.map(answers -> answers.path("needs_reply"))
				.filter(answer -> answer.has("noul"))
				.map(answer -> answer.get("noul").asDouble())
				.map(probability -> {
					ReplyDecision decision;
					if (probability >= config.replyRequiredThreshold()) {
						decision = ReplyDecision.REQUIRED;
					} else if (probability <= config.replyNotRequiredThreshold()) {
						decision = ReplyDecision.NOT_REQUIRED;
					} else {
						decision = ReplyDecision.UNDECIDED;
					}
					log.debug("[{}] needs_reply: p={} (required>={}, notRequired<={}) -> {}",
							context, format(probability), format(config.replyRequiredThreshold()),
							format(config.replyNotRequiredThreshold()), decision);
					decisionRecorder.record(context, "needs_reply",
							switch (decision) {
								case REQUIRED -> "нужен ответ";
								case NOT_REQUIRED -> "ответ не нужен";
								case UNDECIDED -> "не ясно";
							},
							probability, config.replyRequiredThreshold(),
							decision == ReplyDecision.UNDECIDED ? JevDecisionRecorder.Outcome.UNDECIDED : JevDecisionRecorder.Outcome.ACCEPTED);
					return decision;
				})
				.orElse(ReplyDecision.UNDECIDED);
	}


	// ------------------------------------------------------------------
	// Детектор массовых инцидентов
	// ------------------------------------------------------------------

	public Optional<OutageSignal> analyzeOutage(
			String context,
			String text,
			Organization organization,
			List<SupportIncident> activeIncidents
	) {
		if (!jevClient.isEnabled()) {
			return Optional.empty();
		}
		if (isBlank(text)) {
			log.debug("[{}] outage skipped: empty text", context);
			return Optional.empty();
		}
		Map<String, Object> questions = new LinkedHashMap<>();
		Map<String, Object> outageCriteria = new LinkedHashMap<>();
		outageCriteria.put("true", "Клиент сообщает, что сервис или система не работает, недоступна, работает с ошибками или очень медленно");
		outageCriteria.put("false", "Вопрос, консультация, плановая работа, заказ оборудования, благодарность или единичная просьба без сбоя");
		Map<String, Object> outage = new LinkedHashMap<>();
		outage.put("type", "noul");
		outage.put("instructions", "Сообщает ли клиент о сбое в работе ИТ-сервиса?");
		outage.put("criteria", outageCriteria);
		questions.put("is_outage", outage);

		Map<String, ru.ravel.ItDesk.model.Service> serviceByKey = new LinkedHashMap<>();
		Map<String, Object> serviceCriteria = new LinkedHashMap<>();
		for (ru.ravel.ItDesk.model.Service service : availableServices(organization)) {
			serviceByKey.put(service.getCode(), service);
			serviceCriteria.put(service.getCode(), describe(service.getName(), service.getDescription()));
		}
		if (!serviceCriteria.isEmpty()) {
			serviceCriteria.put(NONE, "Ни один из перечисленных сервисов не подходит или это неясно");
			questions.put("service", choice("К какому ИТ-сервису относится проблема?", serviceCriteria));
		}

		Map<String, String> symptomByKey = new LinkedHashMap<>();
		SYMPTOMS.keySet().stream().sorted().forEach(key -> symptomByKey.put(key, key));
		questions.put("symptom", choice("Как проявляется проблема?", new LinkedHashMap<>(SYMPTOMS)));

		Map<String, SupportIncident> incidentByKey = new LinkedHashMap<>();
		Map<String, Object> incidentCriteria = new LinkedHashMap<>();
		for (SupportIncident incident : activeIncidents) {
			String key = incident.getIncidentNumber() == null ? "INC-" + incident.getId() : incident.getIncidentNumber();
			incidentByKey.put(key, incident);
			incidentCriteria.put(key, describe(incident.getTitle(), incident.getPublicDescription()));
		}
		if (!incidentCriteria.isEmpty()) {
			incidentCriteria.put(NONE, "Сообщение не относится ни к одному из этих инцидентов");
			questions.put("active_incident", choice("Относится ли сообщение к одному из открытых инцидентов?", incidentCriteria));
		}

		Map<String, Object> impact = new LinkedHashMap<>();
		impact.put("type", "score");
		impact.put("instructions", "Насколько широко затронуты пользователи по словам клиента?");
		impact.put("criteria", List.of(
				"Один пользователь",
				"Несколько пользователей или отдел",
				"Вся организация или несколько организаций"
		));
		questions.put("impact", impact);

		double minConfidence = settingsService.effective().threshold(JevThreshold.INCIDENT_ANALYSIS);
		return jevClient.evaluate(context, text, questions).map(answers -> {
			double outageProbability = answers.path("is_outage").path("noul").asDouble(0);
			String symptom = pick(context, answers, "symptom", symptomByKey, minConfidence);
			JsonNode impactNode = answers.path("impact");
			Double impactScore = impactNode.has("score") ? impactNode.get("score").asDouble() : null;
			OutageSignal signal = new OutageSignal(
					outageProbability,
					pick(context, answers, "service", serviceByKey, minConfidence),
					symptom == null ? "другое" : symptom,
					pick(context, answers, "active_incident", incidentByKey, minConfidence),
					impactScore
			);
			log.debug("[{}] outage: p={}, impact={}", context, format(outageProbability),
					impactScore == null ? "?" : format(impactScore));
			return signal;
		});
	}


	public Optional<BatchVerdict> verifyBatch(String context, List<String> texts) {
		if (!jevClient.isEnabled() || texts == null || texts.size() < 2) {
			return Optional.empty();
		}
		Map<String, Object> sameCriteria = new LinkedHashMap<>();
		sameCriteria.put("true", "Сообщения описывают один и тот же сбой одного сервиса");
		sameCriteria.put("false", "Сообщения о разных проблемах, совпадение случайное");
		Map<String, Object> same = new LinkedHashMap<>();
		same.put("type", "noul");
		same.put("instructions", "Описывают ли эти сообщения разных клиентов одну и ту же проблему?");
		same.put("criteria", sameCriteria);
		Map<String, Object> severity = new LinkedHashMap<>();
		severity.put("type", "score");
		severity.put("instructions", "Насколько серьёзен сбой, описанный в сообщениях?");
		severity.put("criteria", SEVERITY_LEVELS);
		Map<String, Object> questions = new LinkedHashMap<>();
		questions.put("same_problem", same);
		questions.put("severity", severity);
		Map<String, String> causeByKey = new LinkedHashMap<>();
		PROBABLE_CAUSES.keySet().forEach(key -> causeByKey.put(key, key));
		questions.put("probable_cause", choice("Какова наиболее вероятная причина сбоя по этим сообщениям?",
				new LinkedHashMap<>(PROBABLE_CAUSES)));

		double minConfidence = settingsService.effective().threshold(JevThreshold.INCIDENT_ANALYSIS);
		return jevClient.evaluate(context, texts, questions).map(answers -> {
			double sameProbability = answers.path("same_problem").path("noul").asDouble(0);
			double severityScore = answers.path("severity").path("score").asDouble(1);
			// Округление вниз: между уровнями берём менее серьёзный, чтобы не завышать
			int level = (int) Math.max(0, Math.min(SEVERITY_LEVELS.size() - 1, Math.floor(severityScore)));
			IncidentSeverity incidentSeverity = IncidentSeverity.values()[IncidentSeverity.values().length - 1 - level];
			String cause = pick(context, answers, "probable_cause", causeByKey, minConfidence);
			log.debug("[{}] batch of {}: same_problem p={}, severity score={} -> {}", context, texts.size(),
					format(sameProbability), format(severityScore), incidentSeverity);
			return new BatchVerdict(sameProbability, incidentSeverity, severityScore, cause == null ? "неизвестно" : cause);
		});
	}


	// ------------------------------------------------------------------

	private Map<String, Object> choice(String instructions, Map<String, Object> criteria) {
		Map<String, Object> question = new LinkedHashMap<>();
		question.put("type", "choice");
		question.put("instructions", instructions);
		question.put("criteria", criteria);
		return question;
	}


	private <T> T pick(String context, JsonNode answers, String key, Map<String, T> candidates, double minConfidence) {
		JsonNode answer = answers.path(key);
		if (!answer.hasNonNull("choice")) {
			if (!answer.isMissingNode()) {
				log.warn("[{}] {}: answer without choice: {}", context, key, answer);
			}
			return null;
		}
		String choice = answer.get("choice").asText();
		double confidence = answer.path("confidence").asDouble(0);
		T result = null;
		String outcome;
		JevDecisionRecorder.Outcome recorded;
		if (NONE.equals(choice)) {
			outcome = "none of the options";
			recorded = JevDecisionRecorder.Outcome.NONE;
		} else if (confidence < minConfidence) {
			outcome = "rejected: confidence below " + format(minConfidence);
			recorded = JevDecisionRecorder.Outcome.REJECTED;
		} else if ((result = candidates.get(choice)) == null) {
			outcome = "rejected: unknown option";
			recorded = JevDecisionRecorder.Outcome.REJECTED;
		} else {
			outcome = "accepted";
			recorded = JevDecisionRecorder.Outcome.ACCEPTED;
		}
		log.debug("[{}] {}: choice=«{}», confidence={} -> {}", context, key, choice, format(confidence), outcome);
		decisionRecorder.record(context, key, NONE.equals(choice) ? "ни один" : choice, confidence, minConfidence, recorded);
		return result;
	}


	private String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}


	private String describe(String name, String description) {
		String text = Objects.toString(description, "").trim();
		if (!text.isEmpty()) {
			return isBlank(name) ? text : name + ": " + text;
		}
		return isBlank(name) ? null : name;
	}


	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}

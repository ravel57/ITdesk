package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.IncidentTaskLink;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.model.jev.JevThreshold;
import ru.ravel.ItDesk.repository.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Помощники Jev для автоматизаций (действия jev.* в правилах):
 * решение о новой заявке, подбор статьи базы знаний, проверка восстановления после инцидента,
 * выбор линии и эскалация, поиск дублей среди заявок и инцидентов.
 * <p>
 * Общие правила: решения пишутся в логгер JEV; если Jev выключен, недоступен или не уверен
 * (уверенность ниже «Минимальной уверенности» из настроек), данные не меняются.
 * Тексты в Jev уходят обрезанными, в лог не пишутся.
 */
@Service
@RequiredArgsConstructor
public class JevAssistService {

	private static final Logger log = LoggerFactory.getLogger("JEV");

	private static final String NONE = "__none__";
	/**
	 * Недавно закрытые заявки клиента, по которым возможна повторная проблема.
	 */
	private static final Duration RECENTLY_CLOSED = Duration.ofDays(7);
	/**
	 * Окно поиска дублей среди открытых заявок.
	 */
	private static final Duration DUPLICATE_WINDOW = Duration.ofHours(72);
	/**
	 * Не эскалировать повторно, пока заявка меньше этого времени на текущей линии.
	 */
	private static final Duration MIN_TIME_ON_LINE = Duration.ofMinutes(10);
	private static final int MAX_OPTIONS = 30;
	private static final int MAX_ARTICLES = 100;
	private static final int CONVERSATION_MESSAGES = 8;
	/**
	 * Реплик разговора, которые Jev видит при решении о новой заявке.
	 */
	private static final int DIALOG_MESSAGES = 6;
	/**
	 * Оператор писал клиенту не раньше этого — идёт разговор.
	 */
	private static final Duration ACTIVE_DIALOG = Duration.ofMinutes(30);
	/**
	 * Короткая реплика в активном разговоре по умолчанию считается его продолжением, а не новой проблемой.
	 */
	private static final int SHORT_REPLY_LENGTH = 60;
	private static final int TEXT_LIMIT = 300;
	private static final int TASK_NAME_LIMIT = 80;
	private static final Set<IncidentStatus> RECOVERY_STATUSES = EnumSet.of(IncidentStatus.MONITORING, IncidentStatus.RESTORED);

	private final JevClient jevClient;
	private final JevSettingsService settingsService;
	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;
	private final ServiceRepository serviceRepository;
	private final ServiceCatalogService serviceCatalogService;
	private final PriorityRepository priorityRepository;
	private final StatusRepository statusRepository;
	private final SupportLineRepository supportLineRepository;
	private final IncidentRepository incidentRepository;
	private final IncidentTaskLinkRepository incidentTaskLinkRepository;
	private final TaskService taskService;
	private final ClientService clientService;
	private final KnowledgeService knowledgeService;
	private final IncidentService incidentService;
	private final TaskRoutingService taskRoutingService;
	private final LlmService llmService;
	private final JevDecisionRecorder decisionRecorder;
	private final AfterCommitExecutor afterCommitExecutor;


	// ======================================================================
	// 1. Нужно ли заводить новую заявку по сообщению клиента
	// ======================================================================

	public enum MessageKind {
		NEW_REQUEST,
		EXISTING_TASK,
		REOPEN,
		NO_ACTION
	}


	/**
	 * Вместо «нет открытых заявок» + «текст не спасибо»: Jev смотрит на сообщение вместе с открытыми
	 * и недавно закрытыми заявками клиента и определяет, о каких проблемах оно.
	 * <p>
	 * Jev не делит текст на части, поэтому проблемы различаются по сервисам каталога: за один запрос задаётся
	 * вопрос да/нет по каждому доступному клиенту сервису («есть проблема с «Интернет»?», «…с «1С»?»),
	 * по проблемам вне каталога и по каждой открытой заявке («это та же проблема?»). Заявка заводится
	 * на каждую найденную проблему, которую не покрывает открытая заявка; повтор проблемы из закрытой
	 * заявки получает название «Повторно: …». Две проблемы одного сервиса дают одну заявку.
	 * Если Jev выключен или не уверен — прежнее правило: заявка создаётся, только если открытых нет.
	 *
	 * @return id созданных заявок
	 */
	public List<Long> createTaskIfNeeded(String context, Long clientId, Long messageId, String text) {
		Client client = clientRepository.findById(clientId).orElse(null);
		if (client == null) {
			return List.of();
		}
		ZonedDateTime now = ZonedDateTime.now();
		List<Task> tasks = Objects.requireNonNullElse(client.getTasks(), List.<Task>of()).stream()
				.filter(Objects::nonNull)
				.sorted(Comparator.comparing(Task::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList();
		List<Task> open = tasks.stream().filter(task -> !Boolean.TRUE.equals(task.getCompleted())).limit(10).toList();
		List<Task> recentlyClosed = tasks.stream()
				.filter(task -> Boolean.TRUE.equals(task.getCompleted()))
				.filter(task -> lastChange(task) != null && lastChange(task).isAfter(now.minus(RECENTLY_CLOSED)))
				.limit(5)
				.toList();
		List<ru.ravel.ItDesk.model.Service> services = availableServices(client);
		// Короткий ответ, пока оператор ведёт разговор («готов», «ок, жду»), — продолжение, если Jev не уверен в обратном
		boolean shortReplyInDialog = !isBlank(text) && text.strip().length() <= SHORT_REPLY_LENGTH
				&& isActiveDialog(client, messageId, now);

		MessageAnalysis raw = analyzeMessage(context, text, open, recentlyClosed, services, dialog(client, messageId));
		// тип сообщения не определён и проблем по сервисам Jev уверенно не видит — прежнее правило
		MessageAnalysis analysis = raw != null && raw.kind() == null && raw.serviceProblems().isEmpty() && !raw.otherProblem()
				? null
				: raw;
		if (analysis == null) {
			if (shortReplyInDialog) {
				log.debug("[{}] jev.createTaskIfNeeded: no confident decision, short reply in active dialog -> no new task", context);
				return List.of();
			}
			if (open.isEmpty() && !isBlank(text)) {
				log.debug("[{}] jev.createTaskIfNeeded: no confident decision, no open tasks -> create (fallback)", context);
				return List.of(createTask(clientId, messageId, taskName(text), "", null, context));
			}
			log.debug("[{}] jev.createTaskIfNeeded: no confident decision, client has {} open task(s) -> skip (fallback)",
					context, open.size());
			return List.of();
		}
		if (analysis.kind() == MessageKind.NO_ACTION) {
			log.debug("[{}] jev.createTaskIfNeeded: no request in message -> no new task", context);
			return List.of();
		}
		// Тип ниже порога, но лучшая догадка Jev — «заявка не нужна» или «продолжение открытой»: заявку не заводим,
		// даже если Jev отметил проблемы по сервисам (обычно это проблемы из уже открытых заявок)
		if (analysis.kind() == null
				&& (analysis.kindGuess() == MessageKind.NO_ACTION || analysis.kindGuess() == MessageKind.EXISTING_TASK)) {
			log.debug("[{}] jev.createTaskIfNeeded: kind uncertain, best guess {} -> no new task", context, analysis.kindGuess());
			return List.of();
		}
		boolean confidentlyNew = analysis.kind() == MessageKind.NEW_REQUEST || analysis.kind() == MessageKind.REOPEN;
		if (shortReplyInDialog && !confidentlyNew) {
			log.debug("[{}] jev.createTaskIfNeeded: short reply in active dialog, kind {} -> continuation, no new task",
					context, analysis.kind() == null ? "uncertain" : analysis.kind());
			return List.of();
		}
		double minConfidence = threshold(JevThreshold.CREATE_TASK);

		Task reopened = analysis.relatedClosedTaskId() == null ? null : recentlyClosed.stream()
				.filter(task -> task.getId().equals(analysis.relatedClosedTaskId())).findFirst().orElse(null);
		List<Problem> problems = new ArrayList<>(analysis.serviceProblems().stream().map(Problem::of).toList());
		if (analysis.otherProblem()) {
			problems.add(Problem.OTHER);
		}
		if (problems.isEmpty() && analysis.kind() != MessageKind.EXISTING_TASK) {
			// Jev не отнёс проблему ни к сервису, ни к «прочим», но уверен, что это новый вопрос или повтор
			problems.add(Problem.OTHER);
		}

		// Проблемы, которые уже покрывают открытые заявки: по сервису заявки; заявка без сервиса покрывает «прочее»
		Set<Long> coveredServices = new HashSet<>();
		boolean otherCovered = false;
		for (Task task : open) {
			if (!analysis.matchedOpenTaskIds().contains(task.getId())) {
				continue;
			}
			if (task.getService() != null) {
				coveredServices.add(task.getService().getId());
			} else {
				otherCovered = true;
			}
		}
		if (problems.size() == 1 && !analysis.matchedOpenTaskIds().isEmpty()) {
			// одна проблема и совпадение с открытой заявкой — это продолжение, даже если сервисы разметились по-разному
			otherCovered = true;
			coveredServices.add(problems.getFirst().serviceId());
		}

		List<Long> created = new ArrayList<>();
		boolean multiple = problems.size() > 1;
		for (Problem problem : problems) {
			boolean covered = problem.service() == null ? otherCovered : coveredServices.contains(problem.serviceId());
			if (covered) {
				log.debug("[{}] jev.createTaskIfNeeded: problem «{}» is covered by an open task", context, problem.label());
				continue;
			}
			// Страховка: по сервису уже есть открытая заявка — новая только если Jev уверен, что это новый вопрос
			// и уверенно отличает его от каждой такой заявки
			List<Long> sameService = open.stream()
					.filter(task -> Objects.equals(task.getService() == null ? null : task.getService().getId(), problem.serviceId()))
					.map(Task::getId)
					.toList();
			boolean provenDifferent = confidentlyNew && sameService.stream()
					.allMatch(id -> analysis.openProbability().getOrDefault(id, 1.0) <= 1 - minConfidence);
			if (!sameService.isEmpty() && !provenDifferent) {
				log.debug("[{}] jev.createTaskIfNeeded: problem «{}» — client already has open task(s) {} -> no new task",
						context, problem.label(), sameService);
				continue;
			}
			boolean isRepeat = reopened != null && (problems.size() == 1
					|| (reopened.getService() != null && Objects.equals(reopened.getService().getId(), problem.serviceId())));
			String name = isRepeat
					? taskName("Повторно: " + Objects.toString(reopened.getName(), ""))
					: taskName(multiple ? problem.label() + ": " + text : text);
			String description = isRepeat
					? "Повторное обращение по закрытой заявке #%d".formatted(reopened.getId())
					: multiple ? "В сообщении клиента несколько проблем; эта заявка — по «%s»".formatted(problem.label()) : "";
			// сообщение привязывается к первой заявке, остальные ссылаются на него в описании
			created.add(createTask(clientId, created.isEmpty() ? messageId : null, name, description, problem.service(), context));
		}
		if (created.isEmpty()) {
			log.debug("[{}] jev.createTaskIfNeeded: all problems are covered by open tasks -> no new task", context);
		} else if (created.size() > 1) {
			log.debug("[{}] jev.createTaskIfNeeded: message with {} problems -> {} tasks {}", context, problems.size(),
					created.size(), created);
		}
		return created;
	}


	/**
	 * Проблема из сообщения: сервис каталога или «вне каталога» (service = null).
	 */
	private record Problem(ru.ravel.ItDesk.model.Service service, String label) {
		static final Problem OTHER = new Problem(null, "Другое");

		static Problem of(ru.ravel.ItDesk.model.Service service) {
			return new Problem(service, service.getName());
		}

		Long serviceId() {
			return service == null ? null : service.getId();
		}
	}


	/**
	 * Разметка сообщения Jev: тип, сервисы, о которых клиент пишет, есть ли проблема вне каталога,
	 * с какими открытыми заявками совпадает, повтором какой закрытой является.
	 */
	record MessageAnalysis(
			/* null — Jev не уверен в типе сообщения */
			MessageKind kind,
			/* лучший вариант типа, даже если уверенность ниже порога; null — Jev не ответил */
			MessageKind kindGuess,
			List<ru.ravel.ItDesk.model.Service> serviceProblems,
			boolean otherProblem,
			Set<Long> matchedOpenTaskIds,
			/* вероятность «то же, что открытая заявка» по каждой открытой заявке */
			Map<Long, Double> openProbability,
			Long relatedClosedTaskId
	) {
	}


	MessageAnalysis analyzeMessage(
			String context,
			String text,
			List<Task> open,
			List<Task> recentlyClosed,
			List<ru.ravel.ItDesk.model.Service> services,
			List<String> dialog
	) {
		if (!jevClient.isEnabled() || isBlank(text)) {
			return null;
		}
		Map<String, Object> kinds = new LinkedHashMap<>();
		kinds.put("new_request", "Новый вопрос или проблема, по которой нужно завести заявку");
		if (!open.isEmpty()) {
			kinds.put("existing_task", "Продолжение разговора по открытой заявке: ответ на вопрос или просьбу оператора, "
					+ "уточнение, напоминание");
		}
		if (!recentlyClosed.isEmpty()) {
			kinds.put("reopen", "Проблема из недавно закрытой заявки вернулась или не была решена");
		}
		kinds.put("no_action", "Благодарность, подтверждение, приветствие без сути, короткая реплика в разговоре без нового "
				+ "вопроса («ок», «готов», «жду»), автоответ или спам — заявка не нужна");

		// Вопросы — строго про последнее сообщение (message): открытые заявки и прошлые реплики даны только как контекст
		Map<String, Object> questions = new LinkedHashMap<>();
		questions.put("kind", choice("Что означает последнее сообщение клиента (message) для службы поддержки? "
				+ "Учитывайте предыдущие реплики разговора (dialog).", kinds));
		for (ru.ravel.ItDesk.model.Service service : services) {
			String description = Objects.toString(service.getDescription(), "").trim();
			questions.put("svc_" + service.getId(), yesNo(
					("Пишет ли клиент в последнем сообщении (message) о проблеме или вопросе по сервису «%s»%s? "
							+ "Проблемы из открытых заявок и прошлых реплик не считаются, если клиент не пишет о них снова.").formatted(
							service.getName(), description.isEmpty() ? "" : " (" + limit(description, 150) + ")"),
					"Да, в последнем сообщении есть проблема или вопрос по этому сервису",
					"Нет, в последнем сообщении этот сервис не затронут"));
		}
		questions.put("svc_other", yesNo(
				services.isEmpty()
						? "Есть ли в последнем сообщении (message) проблема или вопрос, требующие работы поддержки?"
						: ("Есть ли в последнем сообщении (message) проблема или вопрос, НЕ связанные ни с одним из сервисов: %s? "
						+ "Проблемы из открытых заявок и прошлых реплик не считаются.").formatted(
						services.stream().map(ru.ravel.ItDesk.model.Service::getName).collect(Collectors.joining(", "))),
				"Да, есть такая проблема или вопрос",
				"Нет"));
		for (Task task : open) {
			questions.put("open_" + task.getId(), yesNo(
					("Относится ли последнее сообщение клиента к его открытой заявке «%s»: та же проблема, продолжение "
							+ "разговора по ней, ответ оператору, уточнение или напоминание?").formatted(limit(task.getName())),
					"Да, относится к этой заявке",
					"Нет, это о другом"));
		}
		Map<String, Long> closedByKey = new LinkedHashMap<>();
		if (!recentlyClosed.isEmpty()) {
			Map<String, Object> related = new LinkedHashMap<>();
			for (Task task : recentlyClosed) {
				closedByKey.put("#" + task.getId(), task.getId());
				related.put("#" + task.getId(), "Закрытая заявка: " + limit(task.getName()));
			}
			related.put(NONE, "Не повтор ни одной из этих заявок");
			questions.put("related_task", choice("Повтором проблемы из какой закрытой заявки клиента является сообщение?", related));
		}

		Map<String, Object> state = new LinkedHashMap<>();
		state.put("message", limit(text, 1000));
		state.put("dialog", dialog);
		state.put("open_tasks", open.stream().map(task -> limit(task.getName())).toList());
		state.put("recently_closed_tasks", recentlyClosed.stream().map(task -> limit(task.getName())).toList());

		Optional<JsonNode> answers = jevClient.evaluate(context, state, questions);
		if (answers.isEmpty()) {
			return null;
		}
		// Неуверенность в типе сообщения не отменяет найденные проблемы по сервисам: тип null = «новый запрос»,
		// если проблемы есть, и резервное правило — если их нет (см. createTaskIfNeeded)
		double minConfidence = threshold(JevThreshold.CREATE_TASK);
		String kind = pick(context, answers.get(), "kind", kinds.keySet(), minConfidence);
		// Проблема по сервису порождает отдельную заявку, поэтому для неё свой порог
		double splitThreshold = threshold(JevThreshold.SPLIT_TASKS);
		List<ru.ravel.ItDesk.model.Service> serviceProblems = services.stream()
				.filter(service -> yes(context, answers.get(), "svc_" + service.getId(), service.getName(), splitThreshold))
				.toList();
		boolean other = yes(context, answers.get(), "svc_other", "другое", splitThreshold);
		Set<Long> matchedOpen = open.stream()
				.filter(task -> yes(context, answers.get(), "open_" + task.getId(), "#" + task.getId(), minConfidence))
				.map(Task::getId)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		Map<Long, Double> openProbability = new HashMap<>();
		for (Task task : open) {
			JsonNode answer = answers.get().path("open_" + task.getId());
			if (answer.has("noul")) {
				openProbability.put(task.getId(), answer.get("noul").asDouble());
			}
		}
		String guess = answers.get().path("kind").path("choice").asText(null);
		MessageKind kindGuess = guess != null && kinds.containsKey(guess) ? MessageKind.valueOf(guess.toUpperCase(Locale.ROOT)) : null;
		String closedKey = pick(context, answers.get(), "related_task", closedByKey.keySet(), minConfidence);
		return new MessageAnalysis(kind == null ? null : MessageKind.valueOf(kind.toUpperCase(Locale.ROOT)), kindGuess,
				serviceProblems, other, matchedOpen, openProbability, closedKey == null ? null : closedByKey.get(closedKey));
	}


	private List<ru.ravel.ItDesk.model.Service> availableServices(Client client) {
		Long organizationId = client.getOrganization() == null ? null : client.getOrganization().getId();
		Set<Long> ids = new HashSet<>();
		for (Map<String, Object> service : serviceCatalogService.getAvailableServices(organizationId)) {
			ids.add(((Number) service.get("id")).longValue());
		}
		return serviceRepository.findAllByActiveTrueOrderByOrderNumberAscNameAsc().stream()
				.filter(service -> ids.contains(service.getId()))
				.limit(MAX_OPTIONS)
				.toList();
	}


	private Long createTask(
			Long clientId,
			Long messageId,
			String name,
			String description,
			ru.ravel.ItDesk.model.Service service,
			String context
	) {
		Task task = taskService.newTaskForSystem(clientId, Task.builder()
				.name(name)
				.description(description)
				.service(service)
				.priority(priorityRepository.findByDefaultSelectionTrue().orElseThrow())
				.status(statusRepository.findByDefaultSelectionTrue().orElseThrow())
				.linkedMessageId(messageId)
				.build());
		log.debug("[{}] jev.createTaskIfNeeded: created task #{} «{}»", context, task.getId(), name);
		return task.getId();
	}


	// ======================================================================
	// 2. Подбор статьи базы знаний
	// ======================================================================

	/**
	 * Подбирает статью базы знаний и пишет оператору внутренний комментарий в чате клиента
	 * (клиент его не видит). Одну и ту же статью одному клиенту повторно не предлагает.
	 */
	public Optional<Knowledge> suggestKnowledgeArticle(String context, Long clientId, String text) {
		if (!jevClient.isEnabled() || isBlank(text)) {
			return Optional.empty();
		}
		List<Knowledge> articles = knowledgeService.getKnowledgeBase().stream()
				.filter(article -> !isBlank(article.getTitle()))
				.limit(MAX_ARTICLES)
				.toList();
		if (articles.isEmpty()) {
			log.debug("[{}] jev.suggestKnowledgeArticle: knowledge base is empty", context);
			return Optional.empty();
		}
		Map<String, Knowledge> byKey = new LinkedHashMap<>();
		Map<String, Object> options = new LinkedHashMap<>();
		for (Knowledge article : articles) {
			String key = "#" + article.getId();
			byKey.put(key, article);
			String excerpt = article.getTexts() == null ? "" : article.getTexts().stream()
					.filter(Objects::nonNull).collect(Collectors.joining(" "));
			options.put(key, isBlank(excerpt) ? article.getTitle() : article.getTitle() + ": " + limit(excerpt, 200));
		}
		options.put(NONE, "Ни одна статья не поможет с этим обращением");
		Optional<JsonNode> answers = jevClient.evaluate(context, limit(text, 1000),
				Map.of("article", choice("Какая статья базы знаний поможет ответить на это обращение?", options)));
		String key = answers.map(node -> pick(context, node, "article", byKey.keySet(), threshold(JevThreshold.KNOWLEDGE_ARTICLE))).orElse(null);
		if (key == null) {
			return Optional.empty();
		}
		Knowledge article = byKey.get(key);
		String marker = "статья базы знаний «%s»".formatted(article.getTitle());
		if (alreadyCommented(clientId, marker)) {
			log.debug("[{}] jev.suggestKnowledgeArticle: «{}» was already suggested to this client", context, article.getTitle());
			return Optional.of(article);
		}
		// Сначала сообщаем, что статья найдена и подсказка готовится, затем (может быть долго — запрос к LLM,
		// «Спросить ИИ») отдельным сообщением саму подсказку. Всё — после коммита, вне транзакции правила.
		String articleText = article.getTexts() == null ? "" : String.join("\n", article.getTexts());
		afterCommitExecutor.run(() -> {
			addComment(clientId, "Jev: нашлась %s. Готовлю подсказку…".formatted(marker));
			String summary = summarizeArticle(context, text, article.getTitle(), articleText);
			addComment(
					clientId, summary == null
							? "Jev: не получилось коротко сформулировать подсказку — откройте статью в базе знаний."
							: summary
			);
		});
		log.debug("[{}] jev.suggestKnowledgeArticle: suggested #{} «{}»", context, article.getId(), article.getTitle());
		return Optional.of(article);
	}


	private String summarizeArticle(String context, String request, String title, String articleText) {
		String prompt = ("Обращение клиента: «%s».\n"
				+ "Статья базы знаний «%s»:\n%s\n\n"
				+ "Кратко, в 2–4 предложениях для оператора поддержки: что из статьи поможет решить это обращение "
				+ "и какие шаги предложить клиенту. Без вступлений.").formatted(limit(request, 500), title, limit(articleText, 3000));
		try {
			String answer = llmService.askLlm(prompt);
			if (isBlank(answer)) {
				return null;
			}
			return limit(answer, 1000);
		} catch (Exception e) {
			log.warn("[{}] jev.suggestKnowledgeArticle: AI summary unavailable, link only: {}", context, e.getMessage());
			return null;
		}
	}


	// ======================================================================
	// 3. Проверка восстановления после инцидента
	// ======================================================================

	public enum RecoveryReport {
		STILL_BROKEN,
		CONFIRMED,
		UNRELATED
	}


	/**
	 * Если заявка клиента связана с инцидентом в статусе «Наблюдение» или «Восстановлен»,
	 * Jev определяет: клиент подтверждает восстановление или сообщает, что проблема сохраняется.
	 * Подтверждение отмечается в инциденте; сохраняющаяся проблема снимает подтверждение,
	 * пишется в таймлайн и уведомляет ответственных за инцидент.
	 */
	public Optional<RecoveryReport> checkIncidentRecovery(String context, Long clientId, Long messageId, String text) {
		Client client = clientRepository.findById(clientId).orElse(null);
		if (client == null || isBlank(text)) {
			return Optional.empty();
		}
		IncidentTaskLink link = Objects.requireNonNullElse(client.getTasks(), List.<Task>of()).stream()
				.filter(Objects::nonNull)
				.map(task -> incidentTaskLinkRepository.findFirstByTaskIdAndActiveTrue(task.getId()).orElse(null))
				.filter(Objects::nonNull)
				.filter(candidate -> RECOVERY_STATUSES.contains(candidate.getIncident().getStatus()))
				.max(Comparator.comparing(candidate -> candidate.getIncident().getId()))
				.orElse(null);
		if (link == null) {
			log.debug("[{}] jev.checkIncidentRecovery: client has no tasks in incidents under recovery", context);
			return Optional.empty();
		}
		if (!jevClient.isEnabled()) {
			return Optional.empty();
		}
		SupportIncident incident = link.getIncident();
		Long taskId = link.getTask().getId();
		Map<String, Object> options = new LinkedHashMap<>();
		options.put("still_broken", "Клиент сообщает, что проблема всё ещё есть или повторилась");
		options.put("confirmed", "Клиент подтверждает, что всё снова работает");
		options.put("unrelated", "Сообщение не о проблеме этого инцидента");
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("message", limit(text, 1000));
		state.put("incident", incident.getTitle() + (isBlank(incident.getPublicDescription())
				? "" : ". " + limit(incident.getPublicDescription())));
		Optional<JsonNode> answers = jevClient.evaluate(context, state,
				Map.of("recovery", choice("Что клиент сообщает о проблеме инцидента после восстановления?", options)));
		String key = answers.map(node -> pick(context, node, "recovery", options.keySet(), threshold(JevThreshold.INCIDENT_RECOVERY))).orElse(null);
		if (key == null) {
			return Optional.empty();
		}
		RecoveryReport report = RecoveryReport.valueOf(key.toUpperCase(Locale.ROOT));
		String source = "клиент #%d, сообщение #%d".formatted(clientId, messageId);
		switch (report) {
			case CONFIRMED -> {
				boolean changed = incidentService.setRecoveryConfirmationForSystem(incident.getId(), taskId, true,
						"Jev: " + source);
				log.debug("[{}] jev.checkIncidentRecovery: {} — client confirms recovery{}", context,
						incident.getIncidentNumber(), changed ? ", task #" + taskId + " marked confirmed" : " (already confirmed)");
			}
			case STILL_BROKEN -> {
				incidentService.setRecoveryConfirmationForSystem(incident.getId(), taskId, false, "Jev: " + source);
				incidentService.reportRecoveryProblemForSystem(incident.getId(), taskId, source);
				log.debug("[{}] jev.checkIncidentRecovery: {} — client says the problem persists, responsible users notified",
						context, incident.getIncidentNumber());
			}
			case UNRELATED -> log.debug("[{}] jev.checkIncidentRecovery: {} — message is not about the incident",
					context, incident.getIncidentNumber());
		}
		return Optional.of(report);
	}


	// ======================================================================
	// 4. Выбор линии поддержки и эскалация
	// ======================================================================

	/**
	 * Выбирает линию поддержки для заявки по её тексту и описаниям линий.
	 *
	 * @return выбранная линия, если её нужно сменить
	 */
	public Optional<SupportLine> selectSupportLine(String context, Task task) {
		if (!jevClient.isEnabled()) {
			return Optional.empty();
		}
		List<SupportLine> lines = activeLines();
		if (lines.size() < 2) {
			log.debug("[{}] jev.selectSupportLine: less than 2 active lines", context);
			return Optional.empty();
		}
		Map<String, SupportLine> byKey = new LinkedHashMap<>();
		Map<String, Object> options = new LinkedHashMap<>();
		for (SupportLine line : lines) {
			byKey.put(line.getName(), line);
			options.put(line.getName(), describeLine(line));
		}
		Optional<JsonNode> answers = jevClient.evaluate(context, taskText(task),
				Map.of("support_line", choice("Какая линия поддержки должна обработать эту заявку?", options)));
		String key = answers.map(node -> pick(context, node, "support_line", byKey.keySet(), threshold(JevThreshold.SUPPORT_LINE))).orElse(null);
		if (key == null || sameId(byKey.get(key), task.getSupportLine())) {
			return Optional.empty();
		}
		return Optional.of(byKey.get(key));
	}


	/**
	 * Нужно ли перевести заявку на следующую (более высокую по уровню) линию: Jev смотрит на заявку
	 * и последние сообщения переписки. Переводит только при уверенном ответе и не раньше, чем заявка
	 * пробыла на текущей линии {@link #MIN_TIME_ON_LINE}.
	 *
	 * @return линия, на которую заявку нужно перевести
	 */
	public Optional<SupportLine> escalationTarget(String context, Task task, Client client) {
		SupportLine current = task.getSupportLine();
		if (current == null || !jevClient.isEnabled()) {
			return Optional.empty();
		}
		ZonedDateTime enteredLine = task.getEnteredCurrentLineAt();
		if (enteredLine != null && enteredLine.isAfter(ZonedDateTime.now().minus(MIN_TIME_ON_LINE))) {
			log.debug("[{}] jev.escalateIfNeeded: task is on line «{}» for less than {} min", context,
					current.getName(), MIN_TIME_ON_LINE.toMinutes());
			return Optional.empty();
		}
		int currentLevel = Objects.requireNonNullElse(current.getLevel(), 1);
		List<SupportLine> higher = activeLines().stream()
				.filter(line -> Objects.requireNonNullElse(line.getLevel(), 1) > currentLevel)
				.toList();
		if (higher.isEmpty()) {
			log.debug("[{}] jev.escalateIfNeeded: «{}» is the highest line", context, current.getName());
			return Optional.empty();
		}
		int nextLevel = higher.stream().mapToInt(line -> Objects.requireNonNullElse(line.getLevel(), 1)).min().orElseThrow();
		List<SupportLine> candidates = higher.stream()
				.filter(line -> Objects.requireNonNullElse(line.getLevel(), 1) == nextLevel)
				.toList();

		Map<String, Object> escalationCriteria = new LinkedHashMap<>();
		escalationCriteria.put("true", "Текущая линия не справится: нужна более глубокая экспертиза, доступы или права, "
				+ "либо предложенные решения не помогли и проблема повторяется");
		escalationCriteria.put("false", "Текущая линия может продолжать работу над заявкой");
		Map<String, Object> escalation = new LinkedHashMap<>();
		escalation.put("type", "noul");
		escalation.put("instructions", "Нужно ли передать заявку на следующую линию поддержки?");
		escalation.put("criteria", escalationCriteria);
		Map<String, Object> questions = new LinkedHashMap<>();
		questions.put("needs_escalation", escalation);
		Map<String, SupportLine> byKey = new LinkedHashMap<>();
		if (candidates.size() > 1) {
			Map<String, Object> options = new LinkedHashMap<>();
			for (SupportLine line : candidates) {
				byKey.put(line.getName(), line);
				options.put(line.getName(), describeLine(line));
			}
			questions.put("target_line", choice("Какой линии передать заявку?", options));
		}
		Map<String, Object> state = new LinkedHashMap<>();
		state.put("task", taskText(task));
		state.put("current_line", describeLine(current));
		state.put("conversation", conversation(client));

		Optional<JsonNode> answers = jevClient.evaluate(context, state, questions);
		if (answers.isEmpty()) {
			return Optional.empty();
		}
		double probability = answers.get().path("needs_escalation").path("noul").asDouble(0);
		double threshold = threshold(JevThreshold.ESCALATION);
		log.debug("[{}] jev.escalateIfNeeded: needs_escalation p={} (threshold {})", context, format(probability), format(threshold));
		decisionRecorder.record(context, "needs_escalation", probability >= threshold ? "эскалировать" : "оставить",
				probability, threshold, probability >= threshold ? JevDecisionRecorder.Outcome.ACCEPTED : JevDecisionRecorder.Outcome.REJECTED);
		if (probability < threshold) {
			return Optional.empty();
		}
		if (candidates.size() == 1) {
			return Optional.of(candidates.getFirst());
		}
		String key = pick(context, answers.get(), "target_line", byKey.keySet(), threshold);
		return Optional.ofNullable(key == null ? null : byKey.get(key));
	}


	public void escalate(String context, Task task, Long clientId, SupportLine target, String reason) {
		String from = task.getSupportLine() == null ? "-" : task.getSupportLine().getName();
		taskRoutingService.routeExistingTask(task.getId(), target.getId(), false, reason);
		addComment(clientId, "Jev: заявка #%d переведена с линии «%s» на «%s»".formatted(task.getId(), from, target.getName()));
		log.debug("[{}] jev: task #{} moved from «{}» to «{}» ({})", context, task.getId(), from, target.getName(), reason);
	}


	// ======================================================================
	// 5. Дубли заявок в контексте инцидентов
	// ======================================================================

	public record DuplicateMatch(Long duplicateOfTaskId, SupportIncident incident, boolean sameClient) {
	}


	/**
	 * Ищет, не дублирует ли заявка уже открытую: сначала среди заявок активных инцидентов,
	 * затем среди открытых заявок за последние {@link #DUPLICATE_WINDOW}. Это только решение — результат
	 * правило кладёт в переменную само ({@code var.duplicate = jev.findDuplicate()}); что делать с найденным
	 * дублем (привязать к инциденту, написать оператору) решает правило через condition/action-ноды.
	 */
	public Optional<DuplicateMatch> findDuplicate(String context, Long taskId) {
		Task task = taskRepository.findById(taskId).orElse(null);
		if (task == null || !jevClient.isEnabled()) {
			return Optional.empty();
		}
		if (incidentTaskLinkRepository.findFirstByTaskIdAndActiveTrue(taskId).isPresent()) {
			log.debug("[{}] jev.findDuplicate: task is already linked to an incident", context);
			return Optional.empty();
		}
		Map<Long, SupportIncident> incidentByTask = new LinkedHashMap<>();
		for (SupportIncident incident : incidentRepository.findAllByStatusNotIn(List.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED))) {
			for (IncidentTaskLink link : incidentTaskLinkRepository.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incident.getId())) {
				incidentByTask.putIfAbsent(link.getTask().getId(), incident);
			}
		}
		LinkedHashMap<Long, Task> candidates = new LinkedHashMap<>();
		incidentByTask.keySet().forEach(id -> taskRepository.findById(id).ifPresent(candidate -> candidates.put(id, candidate)));
		taskRepository.findOpenCreatedSince(ZonedDateTime.now().minus(DUPLICATE_WINDOW), PageRequest.of(0, MAX_OPTIONS))
				.forEach(candidate -> candidates.putIfAbsent(candidate.getId(), candidate));
		candidates.remove(taskId);
		if (candidates.isEmpty()) {
			log.debug("[{}] jev.findDuplicate: no candidate tasks", context);
			return Optional.empty();
		}
		Map<String, Long> byKey = new LinkedHashMap<>();
		Map<String, Object> options = new LinkedHashMap<>();
		candidates.values().stream().limit(MAX_OPTIONS).forEach(candidate -> {
			String key = "#" + candidate.getId();
			byKey.put(key, candidate.getId());
			SupportIncident incident = incidentByTask.get(candidate.getId());
			options.put(key, limit(taskText(candidate)) + (incident == null ? "" : " (инцидент «%s»)".formatted(incident.getTitle())));
		});
		options.put(NONE, "Это отдельная проблема, не дубль ни одной из заявок");
		Optional<JsonNode> answers = jevClient.evaluate(context, taskText(task),
				Map.of("duplicate_of", choice("Описывает ли новая заявка ту же проблему, что одна из этих заявок?", options)));
		String key = answers.map(node -> pick(context, node, "duplicate_of", byKey.keySet(), threshold(JevThreshold.DUPLICATE))).orElse(null);
		if (key == null) {
			return Optional.empty();
		}
		Long duplicateOf = byKey.get(key);
		SupportIncident incident = incidentByTask.get(duplicateOf);
		Long clientId = clientRepository.findByTaskId(taskId).map(Client::getId).orElse(null);
		Long otherClientId = clientRepository.findByTaskId(duplicateOf).map(Client::getId).orElse(null);
		boolean sameClient = clientId != null && clientId.equals(otherClientId);
		log.debug("[{}] jev.findDuplicate: possible duplicate of #{}{}", context, duplicateOf,
				incident == null ? "" : ", incident " + incident.getIncidentNumber());
		return Optional.of(new DuplicateMatch(duplicateOf, incident, sameClient));
	}


	// ======================================================================
	// Общее
	// ======================================================================

	private Map<String, Object> yesNo(String instructions, String yes, String no) {
		Map<String, Object> criteria = new LinkedHashMap<>();
		criteria.put("true", yes);
		criteria.put("false", no);
		Map<String, Object> question = new LinkedHashMap<>();
		question.put("type", "noul");
		question.put("instructions", instructions);
		question.put("criteria", criteria);
		return question;
	}


	/**
	 * Ответ «да» на вопрос да/нет: вероятность не ниже порога. Пишет решение в лог и журнал.
	 */
	private boolean yes(String context, JsonNode answers, String key, String label, double threshold) {
		JsonNode answer = answers.path(key);
		if (!answer.has("noul")) {
			return false;
		}
		double probability = answer.get("noul").asDouble(0);
		boolean result = probability >= threshold;
		log.debug("[{}] {} («{}»): p={} -> {}", context, key, label, format(probability), result ? "yes" : "no");
		decisionRecorder.record(context, key,
				result ? "да: " + label : "нет: " + label, probability, threshold,
				result ? JevDecisionRecorder.Outcome.ACCEPTED : JevDecisionRecorder.Outcome.REJECTED);
		return result;
	}


	private Map<String, Object> choice(String instructions, Map<String, Object> criteria) {
		Map<String, Object> question = new LinkedHashMap<>();
		question.put("type", "choice");
		question.put("instructions", instructions);
		question.put("criteria", criteria);
		return question;
	}


	private double threshold(JevThreshold automation) {
		return settingsService.effective().threshold(automation);
	}


	/**
	 * Вариант, выбранный Jev с уверенностью не ниже порога, или null («ни один», низкая уверенность).
	 */
	private String pick(String context, JsonNode answers, String key, Collection<String> allowed, double threshold) {
		JsonNode answer = answers.path(key);
		if (!answer.hasNonNull("choice")) {
			return null;
		}
		String choice = answer.get("choice").asText();
		double confidence = answer.path("confidence").asDouble(0);
		String outcome;
		String result = null;
		JevDecisionRecorder.Outcome recorded;
		if (NONE.equals(choice)) {
			outcome = "none of the options";
			recorded = JevDecisionRecorder.Outcome.NONE;
		} else if (confidence < threshold) {
			outcome = "rejected: confidence below " + format(threshold);
			recorded = JevDecisionRecorder.Outcome.REJECTED;
		} else if (!allowed.contains(choice)) {
			outcome = "rejected: unknown option";
			recorded = JevDecisionRecorder.Outcome.REJECTED;
		} else {
			outcome = "accepted";
			result = choice;
			recorded = JevDecisionRecorder.Outcome.ACCEPTED;
		}
		log.debug("[{}] {}: choice=«{}», confidence={} -> {}", context, key, choice, format(confidence), outcome);
		decisionRecorder.record(context, key, NONE.equals(choice) ? "ни один" : choice, confidence, threshold, recorded);
		return result;
	}


	private List<SupportLine> activeLines() {
		return supportLineRepository.findAll().stream()
				.filter(line -> !Boolean.FALSE.equals(line.getActive()))
				.sorted()
				.toList();
	}


	private String describeLine(SupportLine line) {
		String description = Objects.toString(line.getDescription(), "").trim();
		String level = "уровень " + Objects.requireNonNullElse(line.getLevel(), 1);
		return description.isEmpty() ? line.getName() + " (" + level + ")" : line.getName() + " (" + level + "): " + description;
	}


	private String taskText(Task task) {
		String description = Objects.toString(task.getDescription(), "").trim();
		return limit(Objects.toString(task.getName(), "") + (description.isEmpty() ? "" : ". " + description), 1000);
	}


	private List<String> conversation(Client client) {
		if (client == null || client.getMessages() == null) {
			return List.of();
		}
		List<Message> messages = client.getMessages().stream()
				.filter(message -> message.getDate() != null && !Boolean.TRUE.equals(message.getDeleted()))
				.filter(message -> !isBlank(message.getText()))
				.sorted()
				.toList();
		return messages.subList(Math.max(0, messages.size() - CONVERSATION_MESSAGES), messages.size()).stream()
				.map(message -> (Boolean.TRUE.equals(message.getIsComment()) ? "комментарий оператора: "
						: Boolean.TRUE.equals(message.getIsSent()) ? "оператор: " : "клиент: ") + limit(message.getText()))
				.toList();
	}


	/**
	 * Предыдущие реплики разговора с клиентом (без текущего сообщения и внутренних комментариев), от старых к новым.
	 */
	List<String> dialog(Client client, Long currentMessageId) {
		if (client == null || client.getMessages() == null) {
			return List.of();
		}
		List<Message> messages = client.getMessages().stream()
				.filter(message -> message.getDate() != null && !Boolean.TRUE.equals(message.getDeleted()))
				.filter(message -> !Boolean.TRUE.equals(message.getIsComment()) && !isBlank(message.getText()))
				.filter(message -> !Objects.equals(message.getId(), currentMessageId))
				.sorted()
				.toList();
		return messages.subList(Math.max(0, messages.size() - DIALOG_MESSAGES), messages.size()).stream()
				.map(message -> (!Boolean.TRUE.equals(message.getIsSent()) ? "клиент: "
						: isSystemMessage(message) ? "автоответ: " : "оператор: ") + limit(message.getText()))
				.toList();
	}


	/**
	 * Оператор (не автоответ) писал клиенту за последние ACTIVE_DIALOG — идёт разговор.
	 */
	boolean isActiveDialog(Client client, Long currentMessageId, ZonedDateTime now) {
		if (client == null || client.getMessages() == null) {
			return false;
		}
		ZonedDateTime since = now.minus(ACTIVE_DIALOG);
		return client.getMessages().stream()
				.filter(message -> !Objects.equals(message.getId(), currentMessageId))
				.filter(message -> Boolean.TRUE.equals(message.getIsSent()) && !Boolean.TRUE.equals(message.getIsComment()))
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()) && !isSystemMessage(message))
				.anyMatch(message -> message.getDate() != null && message.getDate().isAfter(since));
	}


	private boolean isSystemMessage(Message message) {
		return message.getUser() != null && org.hibernate.Hibernate.unproxy(message.getUser()) instanceof SystemUser;
	}


	private boolean alreadyCommented(Long clientId, String marker) {
		return clientRepository.findById(clientId)
				.map(Client::getMessages)
				.map(messages -> messages.stream()
						.filter(message -> Boolean.TRUE.equals(message.getIsComment()))
						.anyMatch(message -> Objects.toString(message.getText(), "").contains(marker)))
				.orElse(false);
	}


	private void addComment(Long clientId, String text) {
		clientService.sendMessageForSystem(clientId, Message.builder()
				.text(text)
				.isComment(true)
				.isSent(true)
				.isRead(true)
				.date(ZonedDateTime.now())
				.build());
	}


	private ZonedDateTime lastChange(Task task) {
		return task.getClosedAt() != null ? task.getClosedAt() : task.getLastActivity();
	}


	private String taskName(String text) {
		String firstLine = Objects.toString(text, "").strip().split("\\R", 2)[0].strip();
		return firstLine.length() <= TASK_NAME_LIMIT ? firstLine : firstLine.substring(0, TASK_NAME_LIMIT - 1).strip() + "…";
	}


	private boolean sameId(SupportLine left, SupportLine right) {
		return left != null && right != null && Objects.equals(left.getId(), right.getId());
	}


	private String limit(String text) {
		return limit(text, TEXT_LIMIT);
	}


	private String limit(String text, int max) {
		String value = Objects.toString(text, "").strip();
		return value.length() <= max ? value : value.substring(0, max) + "…";
	}


	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}


	private String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

}

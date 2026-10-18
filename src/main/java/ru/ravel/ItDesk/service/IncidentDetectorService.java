package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import ru.ravel.ItDesk.model.incident.IncidentDetectorMode;
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.IncidentSignal;
import ru.ravel.ItDesk.model.incident.IncidentSignalDecision;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.model.jev.JevThreshold;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.IncidentSignalRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Детектор массовых инцидентов: Jev размечает каждое входящее сообщение (сбой ли это, какой сервис и симптом,
 * относится ли к открытому инциденту), а решение о массовости принимается здесь — по числу разных клиентов
 * с одинаковым сервисом и симптомом в скользящем окне. При достижении порога Jev проверяет пачку целиком.
 * <p>
 * Режимы (Настройки → Jev):
 * <ul>
 *     <li>NOTIFY_ONLY — при срабатывании ответственные получают уведомление, данные не меняются;</li>
 *     <li>CREATE_DRAFT — создаётся инцидент в статусе DRAFT с заполненными полями, к нему привязываются
 *     заявки клиентов и следующие похожие обращения; ответственные получают уведомление о черновике.</li>
 * </ul>
 * Получатели уведомлений: владелец и исполнители сервиса, ответственный и участники линии;
 * если таких нет — администраторы. Подробности решений дополнительно пишутся в логгер JEV.
 * Jev не генерирует текст: серьёзность и вероятную причину он выбирает из списков, остальные поля
 * заполняются по данным (сервис, клиенты, заявки, время) и шаблонам.
 */
@Service
@RequiredArgsConstructor
public class IncidentDetectorService {

	private static final Logger log = LoggerFactory.getLogger("JEV");

	/** Сколько открытых инцидентов предлагать Jev для сопоставления. */
	private static final int MAX_ACTIVE_INCIDENTS = 50;
	/** Сколько последних сообщений отправлять на проверку пачки и сколько символов из каждого. */
	private static final int MAX_BATCH_MESSAGES = 20;
	private static final int MAX_BATCH_MESSAGE_CHARS = 500;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
	private static final Set<IncidentSignalDecision> OUTAGE_DECISIONS = EnumSet.of(
			IncidentSignalDecision.COUNTED,
			IncidentSignalDecision.TRIGGERED,
			IncidentSignalDecision.BATCH_REJECTED,
			IncidentSignalDecision.COOLDOWN
	);
	/**
	 * Тревога мониторинга по самому сервису. UNAVAILABLE и UNKNOWN — недоступность Zabbix или интеграции,
	 * а не сервиса, поэтому порог разных клиентов не снижают.
	 */
	private static final Set<ServiceMonitoringStatus> MONITORING_ALERTS = EnumSet.of(
			ServiceMonitoringStatus.WARNING,
			ServiceMonitoringStatus.CRITICAL
	);
	/** Интервал обновлений инцидента по серьёзности, минут. */
	private static final Map<IncidentSeverity, Integer> UPDATE_INTERVALS = Map.of(
			IncidentSeverity.P1, 15,
			IncidentSeverity.P2, 30,
			IncidentSeverity.P3, 60,
			IncidentSeverity.P4, 120
	);
	private static final List<String> IMPACT_LABELS = List.of(
			"отдельные пользователи",
			"несколько пользователей или отделы",
			"организации целиком"
	);

	private final JevService jevService;
	private final JevSettingsService settingsService;
	private final IncidentSignalRepository signalRepository;
	private final IncidentRepository incidentRepository;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final TaskRepository taskRepository;
	private final IncidentService incidentService;
	private final AppSettingsService appSettingsService;
	private final UserNotificationService userNotificationService;
	private final UserRepository userRepository;
	private final PlatformTransactionManager transactionManager;
	private final JevDecisionRecorder decisionRecorder;


	public Optional<IncidentSignal> handleIncomingMessage(String context, Long clientId, Long messageId, String text) {
		Client client = clientRepository.findById(clientId).orElse(null);
		if (client == null) {
			log.warn("[{}] incident detector: client not found", context);
			return Optional.empty();
		}
		Organization organization = client.getOrganization();
		List<SupportIncident> activeIncidents = incidentRepository
				.findAllByStatusNotIn(List.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED)).stream()
				.sorted(Comparator.comparing(SupportIncident::getId).reversed())
				.limit(MAX_ACTIVE_INCIDENTS)
				.toList();

		Optional<JevService.OutageSignal> analyzed = jevService.analyzeOutage(context, text, organization, activeIncidents);
		if (analyzed.isEmpty()) {
			log.info("[{}] incident detector: no answer from Jev, message skipped", context);
			return Optional.empty();
		}
		JevService.OutageSignal outage = analyzed.get();
		IncidentSignal signal = IncidentSignal.builder()
				.messageId(messageId)
				.clientId(clientId)
				.organizationId(organization == null ? null : organization.getId())
				.serviceId(outage.service() == null ? null : outage.service().getId())
				.symptom(outage.symptom())
				.outageProbability(outage.outageProbability())
				.impactScore(outage.impactScore())
				.createdAt(ZonedDateTime.now())
				.build();
		signal.setDecision(decide(context, signal, outage, text));
		return Optional.of(signalRepository.save(signal));
	}


	private IncidentSignalDecision decide(String context, IncidentSignal signal, JevService.OutageSignal outage, String text) {
		JevSettingsService.IncidentDetectorConfig config = settingsService.incidentDetector();
		boolean createDrafts = config.mode() == IncidentDetectorMode.CREATE_DRAFT;

		SupportIncident matched = outage.matchedIncident();
		if (matched != null) {
			attachToIncident(context, signal, matched, createDrafts, "Jev matched an open incident");
			return IncidentSignalDecision.MATCHED_INCIDENT;
		}

		boolean isOutage = outage.outageProbability() >= config.outageThreshold();
		decisionRecorder.record(context, "is_outage", isOutage ? "сбой" : "не сбой", outage.outageProbability(),
				config.outageThreshold(), isOutage ? JevDecisionRecorder.Outcome.ACCEPTED : JevDecisionRecorder.Outcome.REJECTED);
		if (outage.outageProbability() < config.outageThreshold()) {
			log.info("[{}] incident detector: not an outage (p={} < {})", context,
					format(outage.outageProbability()), format(config.outageThreshold()));
			return IncidentSignalDecision.IGNORED;
		}
		if (outage.service() == null) {
			log.info("[{}] incident detector: outage, but service is unclear — not counted", context);
			return IncidentSignalDecision.IGNORED;
		}

		String key = "service «%s», symptom «%s»".formatted(outage.service().getName(), outage.symptom());
		ZonedDateTime now = signal.getCreatedAt();
		ZonedDateTime windowStart = now.minusMinutes(config.windowMinutes());
		List<IncidentSignal> window = signalRepository.findWindow(
				signal.getServiceId(), signal.getSymptom(), windowStart, OUTAGE_DECISIONS);
		Set<Long> clients = window.stream().map(IncidentSignal::getClientId).collect(Collectors.toCollection(LinkedHashSet::new));
		clients.add(signal.getClientId());
		Set<Long> organizations = window.stream().map(IncidentSignal::getOrganizationId).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (signal.getOrganizationId() != null) {
			organizations.add(signal.getOrganizationId());
		}

		ServiceMonitoringStatus monitoring = outage.service().getMonitoringStatus();
		boolean monitoringAlert = MONITORING_ALERTS.contains(monitoring);
		int required = monitoringAlert ? Math.max(2, config.minClients() - 1) : config.minClients();
		String progress = "%d/%d clients in %d min%s".formatted(clients.size(), required, config.windowMinutes(),
				monitoringAlert ? " (monitoring: " + monitoring + ")" : "");
		if (clients.size() < required) {
			log.info("[{}] incident detector: counted {}, p={} -> {}", context, key,
					format(outage.outageProbability()), progress);
			return IncidentSignalDecision.COUNTED;
		}

		if (config.cooldownMinutes() > 0) {
			ZonedDateTime cooldownStart = now.minusMinutes(config.cooldownMinutes());
			Optional<SupportIncident> recentIncident = signalRepository
					.findFirstByServiceIdAndSymptomAndDecisionAndMatchedIncidentIdIsNotNullAndCreatedAtAfterOrderByCreatedAtDesc(
							signal.getServiceId(), signal.getSymptom(), IncidentSignalDecision.TRIGGERED, cooldownStart)
					.flatMap(triggered -> incidentRepository.findById(triggered.getMatchedIncidentId()))
					.filter(incident -> incident.getStatus().isActive());
			if (recentIncident.isPresent()) {
				// Jev не сопоставил сообщение с инцидентом, но сервис и симптом совпадают с недавним срабатыванием
				attachToIncident(context, signal, recentIncident.get(), createDrafts, "same service and symptom as the trigger");
				return IncidentSignalDecision.MATCHED_INCIDENT;
			}
			// В режиме черновиков паузу включают только срабатывания, по которым создан инцидент:
			// срабатывания из режима логирования не должны блокировать создание после переключения режима
			boolean recentlyTriggered = createDrafts
					? signalRepository.existsByServiceIdAndSymptomAndDecisionAndMatchedIncidentIdIsNotNullAndCreatedAtAfter(
							signal.getServiceId(), signal.getSymptom(), IncidentSignalDecision.TRIGGERED, cooldownStart)
					: signalRepository.existsByServiceIdAndSymptomAndDecisionAndCreatedAtAfter(
							signal.getServiceId(), signal.getSymptom(), IncidentSignalDecision.TRIGGERED, cooldownStart);
			if (recentlyTriggered) {
				log.info("[{}] incident detector: {} -> {}, but already triggered within {} min (cooldown)",
						context, key, progress, config.cooldownMinutes());
				return IncidentSignalDecision.COOLDOWN;
			}
		}

		List<String> texts = batchTexts(window, text);
		Optional<JevService.BatchVerdict> verdict = jevService.verifyBatch(context, texts);
		if (verdict.isEmpty()) {
			log.info("[{}] incident detector: {} -> {}, batch verification unavailable — counted only", context, key, progress);
			return IncidentSignalDecision.COUNTED;
		}
		double minConfidence = settingsService.effective().threshold(JevThreshold.INCIDENT_ANALYSIS);
		boolean sameProblem = verdict.get().sameProblemProbability() >= minConfidence;
		decisionRecorder.record(context, "same_problem", sameProblem ? "одна проблема" : "разные проблемы",
				verdict.get().sameProblemProbability(), minConfidence,
				sameProblem ? JevDecisionRecorder.Outcome.ACCEPTED : JevDecisionRecorder.Outcome.REJECTED);
		if (verdict.get().sameProblemProbability() < minConfidence) {
			log.info("[{}] incident detector: {} -> {}, but messages describe different problems (p={} < {})",
					context, key, progress, format(verdict.get().sameProblemProbability()), format(minConfidence));
			return IncidentSignalDecision.BATCH_REJECTED;
		}

		List<IncidentSignal> batch = new ArrayList<>(window);
		batch.add(signal);
		IncidentSeverity severity = limitSeverity(verdict.get().severity(), clients.size(), config.p1MinClients());
		SystemDraft draft = buildDraft(outage, verdict.get(), severity, batch, clients, organizations, monitoring,
				config, windowStart);

		String summary = "%s: %d клиент(ов) за %d мин, серьёзность %s, вероятная причина — %s".formatted(
				draft.draft().title(), clients.size(), config.windowMinutes(), severity, verdict.get().probableCause());
		if (!createDrafts) {
			notifyRecipients(context, draft.recipientIds(),
					"Обнаружен массовый сбой. " + summary + ". Инцидент не создан (режим «Только уведомлять»).");
			log.warn("[{}] incident detector TRIGGERED: {} -> {}, organizations={}, severity={}{}, cause={} -> "
							+ "[notify-only] notified {} user(s); draft «{}» would have {} task(s), leader={}, line={}, messages {}",
					context, key, progress, organizations.size(), severity, severityNote(verdict.get().severity(), severity),
					verdict.get().probableCause(), draft.recipientIds().size(), draft.draft().title(),
					draft.draft().taskIds().size(), draft.leaderName(), draft.lineName(), draft.messageIds());
			return IncidentSignalDecision.TRIGGERED;
		}

		SupportIncident incident = incidentService.createDraftForSystem(draft.draft());
		signal.setMatchedIncidentId(incident.getId());
		for (IncidentSignal previous : window) {
			if (previous.getMatchedIncidentId() == null) {
				previous.setMatchedIncidentId(incident.getId());
				signalRepository.save(previous);
			}
		}
		notifyRecipients(context, draft.recipientIds(), "Создан черновик инцидента %s. %s. Проверьте и объявите инцидент."
				.formatted(incident.getIncidentNumber(), summary));
		log.warn("[{}] incident detector TRIGGERED: {} -> {}, severity={}{}, cause={} -> created draft {} «{}», "
						+ "tasks linked: {}, leader={}, line={}, messages {}, notified {} user(s)",
				context, key, progress, severity, severityNote(verdict.get().severity(), severity),
				verdict.get().probableCause(), incident.getIncidentNumber(), incident.getTitle(),
				draft.draft().taskIds(), draft.leaderName(), draft.lineName(), draft.messageIds(),
				draft.recipientIds().size());
		return IncidentSignalDecision.TRIGGERED;
	}


	/**
	 * Относит сообщение к открытому инциденту: в режиме CREATE_DRAFT привязывает заявку клиента
	 * и пишет запись в таймлайн.
	 */
	private void attachToIncident(String context, IncidentSignal signal, SupportIncident incident, boolean apply, String reason) {
		signal.setMatchedIncidentId(incident.getId());
		Optional<Task> task = findTask(signal.getClientId(), signal.getMessageId(),
				signal.getCreatedAt().minusMinutes(settingsService.incidentDetector().windowMinutes()));
		if (!apply) {
			log.info("[{}] incident detector: matches {} «{}» ({}) -> [notify-only] task {} not linked",
					context, incident.getIncidentNumber(), incident.getTitle(), reason,
					task.map(t -> "#" + t.getId()).orElse("(no task yet)"));
			return;
		}
		List<Long> linked = task.map(t -> incidentService.linkTasksForSystem(incident.getId(), List.of(t.getId())))
				.orElse(List.of());
		incidentService.addSystemNote(incident.getId(), "Ещё одно похожее обращение: клиент #%d, сообщение #%d%s".formatted(
				signal.getClientId(), signal.getMessageId(),
				linked.isEmpty() ? "" : ", заявка #" + linked.getFirst()));
		log.info("[{}] incident detector: matches {} «{}» ({}) -> {}", context, incident.getIncidentNumber(),
				incident.getTitle(), reason,
				linked.isEmpty()
						? task.map(t -> "task #" + t.getId() + " already linked to an incident").orElse("no task to link yet")
						: "linked task #" + linked.getFirst());
	}


	/**
	 * P1 только при большом числе клиентов: иначе не выше P2.
	 */
	static IncidentSeverity limitSeverity(IncidentSeverity jevSeverity, int clients, int p1MinClients) {
		if (jevSeverity == IncidentSeverity.P1 && clients < p1MinClients) {
			return IncidentSeverity.P2;
		}
		return jevSeverity;
	}


	private String severityNote(IncidentSeverity jevSeverity, IncidentSeverity applied) {
		return jevSeverity == applied ? "" : " (Jev: " + jevSeverity + ", limited by clients count)";
	}


	private record SystemDraft(
			IncidentService.SystemIncidentDraft draft,
			List<Long> messageIds,
			String leaderName,
			String lineName,
			List<Long> recipientIds
	) {
	}


	/**
	 * Уведомление через стандартный механизм (колокольчик и история уведомлений).
	 * Отправляется после коммита, чтобы сбой доставки не откатывал созданный инцидент.
	 */
	private void notifyRecipients(String context, List<Long> recipientIds, String message) {
		// После коммита исходной транзакции запись истории уведомлений нужна в отдельной транзакции
		TransactionTemplate newTransaction = new TransactionTemplate(transactionManager);
		newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		Runnable send = () -> {
			for (Long userId : recipientIds) {
				try {
					newTransaction.executeWithoutResult(status -> userNotificationService.send(
							new UserNotification(UserNotificationEvent.INCIDENT_DETECTED, message, userId)));
				} catch (Exception e) {
					log.warn("[{}] incident detector: notification to user #{} failed: {}", context, userId, e.getMessage());
				}
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					send.run();
				}
			});
		} else {
			send.run();
		}
	}


	/**
	 * Владелец и исполнители сервиса, ответственный и участники линии; если никого нет — администраторы.
	 */
	private List<Long> recipients(ru.ravel.ItDesk.model.Service service, SupportLine line) {
		LinkedHashSet<User> users = new LinkedHashSet<>();
		if (service.getOwner() != null) users.add(service.getOwner());
		if (service.getExecutors() != null) users.addAll(service.getExecutors());
		if (line != null) {
			if (line.getResponsible() != null) users.add(line.getResponsible());
			if (line.getMembers() != null) users.addAll(line.getMembers());
		}
		List<Long> ids = users.stream()
				.filter(Objects::nonNull)
				.filter(User::isEnabled)
				.map(User::getId)
				.filter(Objects::nonNull)
				.distinct()
				.toList();
		if (!ids.isEmpty()) {
			return ids;
		}
		return userRepository.findAll().stream()
				.filter(User::isEnabled)
				.filter(user -> user.getAuthorities() != null && user.getAuthorities().contains(Role.ADMIN))
				.map(User::getId)
				.filter(Objects::nonNull)
				.toList();
	}


	private SystemDraft buildDraft(
			JevService.OutageSignal outage,
			JevService.BatchVerdict verdict,
			IncidentSeverity severity,
			List<IncidentSignal> batch,
			Set<Long> clients,
			Set<Long> organizations,
			ServiceMonitoringStatus monitoring,
			JevSettingsService.IncidentDetectorConfig config,
			ZonedDateTime windowStart
	) {
		ru.ravel.ItDesk.model.Service service = outage.service();
		String symptom = outage.symptom();
		String symptomDescription = JevService.SYMPTOMS.getOrDefault(symptom, symptom);
		String cause = verdict.probableCause();
		List<Long> messageIds = batch.stream().map(IncidentSignal::getMessageId).toList();
		ZonedDateTime startedAt = batch.stream().map(IncidentSignal::getCreatedAt).min(Comparator.naturalOrder()).orElse(windowStart);

		List<Task> tasks = batch.stream()
				.map(item -> findTask(item.getClientId(), item.getMessageId(), windowStart))
				.flatMap(Optional::stream)
				.collect(Collectors.toMap(Task::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new))
				.values().stream().toList();

		SupportLine line = service.getDefaultSupportLine();
		if (line == null) {
			// у сервиса нет линии по умолчанию — берём самую частую линию среди заявок пачки
			line = tasks.stream()
					.map(Task::getSupportLine)
					.filter(Objects::nonNull)
					.collect(Collectors.groupingBy(SupportLine::getId, LinkedHashMap::new, Collectors.toList()))
					.values().stream()
					.max(Comparator.comparingInt(List::size))
					.map(List::getFirst)
					.orElse(null);
		}
		User owner = service.getOwner();
		List<Long> participants = service.getExecutors() == null ? List.of() : service.getExecutors().stream()
				.map(User::getId)
				.filter(Objects::nonNull)
				.filter(id -> owner == null || !id.equals(owner.getId()))
				.toList();

		double averageImpact = batch.stream().map(IncidentSignal::getImpactScore).filter(Objects::nonNull)
				.mapToDouble(Double::doubleValue).average().orElse(0);
		String impactLabel = IMPACT_LABELS.get((int) Math.max(0, Math.min(IMPACT_LABELS.size() - 1, Math.round(averageImpact))));
		String organizationNames = organizations.isEmpty() ? "" : clientRepository.findAllById(clients).stream()
				.map(Client::getOrganization)
				.filter(Objects::nonNull)
				.map(Organization::getName)
				.filter(Objects::nonNull)
				.distinct()
				.collect(Collectors.joining(", "));
		ZoneId zone = zone();

		String title = "Массовые обращения: %s — %s".formatted(service.getName(), symptom);
		StringBuilder description = new StringBuilder()
				.append("Создан автоматически детектором Jev.\n")
				.append("Сервис: ").append(service.getName()).append(". Симптом: ").append(symptom)
				.append(" (").append(symptomDescription.toLowerCase(Locale.ROOT)).append(").\n")
				.append("Обращений: ").append(batch.size()).append(" от ").append(clients.size()).append(" клиентов")
				.append(organizations.isEmpty() ? "" : ", организаций: " + organizations.size())
				.append(" за ").append(config.windowMinutes()).append(" мин; первое — ")
				.append(TIME.format(startedAt.withZoneSameInstant(zone))).append(".\n")
				.append("Проверка пачки: одна проблема с вероятностью ").append(format(verdict.sameProblemProbability()))
				.append(", серьёзность по оценке Jev — ").append(verdict.severity())
				.append(" (оценка ").append(format(verdict.severityScore())).append(" из 3)");
		if (verdict.severity() != severity) {
			description.append(", снижена до ").append(severity)
					.append(": клиентов меньше ").append(config.p1MinClients());
		}
		description.append(".\n")
				.append("Вероятная причина (оценка Jev): ").append(cause).append(" — ")
				.append(JevService.PROBABLE_CAUSES.getOrDefault(cause, "").toLowerCase(Locale.ROOT)).append(".\n");
		if (monitoring != null && monitoring != ServiceMonitoringStatus.NOT_CONFIGURED) {
			description.append("Мониторинг сервиса: ").append(monitoring).append(".\n");
		}
		description.append("Сообщения: ").append(messageIds.stream().map(id -> "#" + id).collect(Collectors.joining(", ")))
				.append(tasks.isEmpty() ? "" : "; заявки: " + tasks.stream().map(t -> "#" + t.getId()).collect(Collectors.joining(", ")))
				.append(".\nПроверьте данные и переведите инцидент в «Проверка» или «Объявлен».");

		String publicDescription = ("Наблюдаются проблемы с сервисом «%s»: %s. Мы получили обращения от нескольких "
				+ "пользователей и уже разбираемся. Сообщим, как только появятся новости.")
				.formatted(service.getName(), symptomDescription.toLowerCase(Locale.ROOT));
		String impactSummary = "Затронуто клиентов: %d%s. Охват по словам клиентов (оценка Jev): %s."
				.formatted(clients.size(),
						organizationNames.isBlank() ? "" : ", организаций: %d (%s)".formatted(organizations.size(), organizationNames),
						impactLabel);

		IncidentService.SystemIncidentDraft draft = new IncidentService.SystemIncidentDraft(
				title,
				description.toString(),
				publicDescription,
				impactSummary,
				severity,
				service.getId(),
				line == null ? null : line.getId(),
				owner == null ? null : owner.getId(),
				owner == null ? null : owner.getId(),
				participants,
				clients,
				tasks.stream().map(Task::getId).toList(),
				startedAt,
				UPDATE_INTERVALS.get(severity)
		);
		return new SystemDraft(
				draft,
				messageIds,
				owner == null ? "-" : Objects.toString(owner.getUsername(), "#" + owner.getId()),
				line == null ? "-" : line.getName(),
				recipients(service, line)
		);
	}


	/**
	 * Заявка клиента по сообщению: сначала явно связанная с сообщением, затем последняя открытая заявка
	 * клиента, созданная в окне (её обычно создаёт правило «новая заявка» по этому же сообщению).
	 */
	private Optional<Task> findTask(Long clientId, Long messageId, ZonedDateTime since) {
		List<Task> linked = taskRepository.findAllByClientIdAndLinkedMessageId(clientId, messageId);
		if (!linked.isEmpty()) {
			return Optional.of(linked.getFirst());
		}
		List<Task> containing = taskRepository.findAllByMessageId(messageId);
		if (!containing.isEmpty()) {
			return Optional.of(containing.getFirst());
		}
		return taskRepository.findOpenByClientIdCreatedSince(clientId, since).stream().findFirst();
	}


	private ZoneId zone() {
		try {
			return ZoneId.of(appSettingsService.getGeneralSettings().getTimezone());
		} catch (Exception e) {
			return ZoneId.systemDefault();
		}
	}


	private List<String> batchTexts(List<IncidentSignal> window, String currentText) {
		List<Long> ids = window.stream()
				.map(IncidentSignal::getMessageId)
				.skip(Math.max(0, window.size() - (MAX_BATCH_MESSAGES - 1)))
				.toList();
		Map<Long, String> textById = messageRepository.findAllById(ids).stream()
				.filter(message -> message.getText() != null && !message.getText().isBlank())
				.collect(Collectors.toMap(Message::getId, Message::getText, (left, right) -> left));
		List<String> texts = new ArrayList<>();
		for (Long id : ids) {
			String messageText = textById.get(id);
			if (messageText != null) {
				texts.add(truncate(messageText));
			}
		}
		texts.add(truncate(currentText));
		return texts;
	}


	private String truncate(String text) {
		return text.length() > MAX_BATCH_MESSAGE_CHARS ? text.substring(0, MAX_BATCH_MESSAGE_CHARS) : text;
	}


	private String format(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}

}

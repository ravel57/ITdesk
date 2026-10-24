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
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.IncidentSignal;
import ru.ravel.ItDesk.model.incident.IncidentSignalDecision;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.IncidentSignalRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;


/**
 * Кирпичики детектора массовых инцидентов Jev. Само решение («сколько обращений, за какое окно, для какой
 * организации, какой порог, что делать при срабатывании») принимает правило автоматизации «Детектор массовых
 * инцидентов (Jev)» из condition/action-нод; здесь только отдельные шаги, которые оно вызывает:
 * <ul>
 *     <li>{@code jev.analyzeOutage()} — {@link #analyze}: Jev размечает сообщение, сигнал сохраняется;</li>
 *     <li>{@code incident.countSignals(окно[, область])} — {@link #count}: сколько разных клиентов в окне;</li>
 *     <li>{@code incident.checkCooldown(минуты, толькоСИнцидентом)} — {@link #cooldown}: пауза после срабатывания;</li>
 *     <li>{@code jev.verifyBatch()} — {@link #verifyBatch}: Jev проверяет пачку целиком;</li>
 *     <li>{@code incident.markSignal / attachSignal / createDraftFromSignals / notifyResponsible} —
 *     {@link #mark}, {@link #attach}, {@link #createDraft}, {@link #notifyResponsible}.</li>
 * </ul>
 * Получатели уведомлений: владелец и исполнители сервиса, ответственный и участники линии;
 * если таких нет — администраторы. Jev не генерирует текст: серьёзность и вероятную причину он выбирает
 * из списков, остальные поля черновика заполняются по данным (сервис, клиенты, заявки, время) и шаблонам.
 */
@Service
@RequiredArgsConstructor
public class IncidentDetectorService {

	private static final Logger log = LoggerFactory.getLogger("JEV");

	/** Сколько открытых инцидентов предлагать Jev для сопоставления (предел размера запроса к модели). */
	private static final int MAX_ACTIVE_INCIDENTS = 50;
	/** Сколько последних сообщений отправлять на проверку пачки и сколько символов из каждого (предел размера запроса). */
	private static final int MAX_BATCH_MESSAGES = 20;
	private static final int MAX_BATCH_MESSAGE_CHARS = 500;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
	/** Решения, при которых сигнал считается обращением о сбое в окне. */
	private static final Set<IncidentSignalDecision> OUTAGE_DECISIONS = EnumSet.of(
			IncidentSignalDecision.COUNTED,
			IncidentSignalDecision.TRIGGERED,
			IncidentSignalDecision.BATCH_REJECTED,
			IncidentSignalDecision.COOLDOWN
	);
	private static final List<String> IMPACT_LABELS = List.of(
			"отдельные пользователи",
			"несколько пользователей или отделы",
			"организации целиком"
	);

	private final JevService jevService;
	private final IncidentSignalRepository signalRepository;
	private final IncidentRepository incidentRepository;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final TaskRepository taskRepository;
	private final ServiceRepository serviceRepository;
	private final IncidentService incidentService;
	private final AppSettingsService appSettingsService;
	private final UserNotificationService userNotificationService;
	private final UserRepository userRepository;
	private final PlatformTransactionManager transactionManager;
	private final JevDecisionRecorder decisionRecorder;


	// ======================================================================
	// 1. Разметка сообщения
	// ======================================================================

	/** Сигнал по сообщению: решение по нему пока {@link IncidentSignalDecision#IGNORED}, пока правило не отметит иначе. */
	public record Analysis(IncidentSignal signal, JevService.OutageSignal outage) {
	}


	public Optional<Analysis> analyze(String context, Long clientId, Long messageId, String text) {
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
			log.debug("[{}] incident detector: no answer from Jev, message skipped", context);
			return Optional.empty();
		}
		JevService.OutageSignal outage = analyzed.get();
		IncidentSignal signal = signalRepository.save(IncidentSignal.builder()
				.messageId(messageId)
				.clientId(clientId)
				.organizationId(organization == null ? null : organization.getId())
				.serviceId(outage.service() == null ? null : outage.service().getId())
				.symptom(outage.symptom())
				.outageProbability(outage.outageProbability())
				.impactScore(outage.impactScore())
				.decision(IncidentSignalDecision.IGNORED)
				.createdAt(ZonedDateTime.now())
				.build());
		// порог «это сбой» задаёт правило, поэтому в журнале решений — только оценка модели
		decisionRecorder.record(context, "is_outage", "оценка вероятности сбоя", outage.outageProbability(), null,
				JevDecisionRecorder.Outcome.UNDECIDED);
		return Optional.of(new Analysis(signal, outage));
	}


	public Optional<IncidentSignal> findSignal(Long id) {
		return id == null ? Optional.empty() : signalRepository.findById(id);
	}


	public List<IncidentSignal> findSignals(Collection<Long> ids) {
		if (ids == null || ids.isEmpty()) {
			return List.of();
		}
		return signalRepository.findAllById(ids).stream()
				.sorted(Comparator.comparing(IncidentSignal::getCreatedAt))
				.toList();
	}


	// ======================================================================
	// 2. Подсчёт обращений в окне
	// ======================================================================

	public record WindowCount(
			List<IncidentSignal> window,
			Set<Long> clients,
			Set<Long> organizations,
			ZonedDateTime windowStart
	) {
		/** Обращений в окне вместе с текущим. */
		public int messages() {
			return window.size() + 1;
		}
	}


	/**
	 * Обращения о том же сервисе и симптоме за {@code windowMinutes} до текущего. С {@code sameOrganizationOnly}
	 * считаются только обращения клиентов той же организации, что и у текущего.
	 */
	public WindowCount count(IncidentSignal signal, int windowMinutes, boolean sameOrganizationOnly) {
		ZonedDateTime windowStart = signal.getCreatedAt().minusMinutes(windowMinutes);
		List<IncidentSignal> window = signalRepository.findWindow(
						signal.getServiceId(), signal.getSymptom(), windowStart, OUTAGE_DECISIONS).stream()
				.filter(item -> !sameOrganizationOnly || Objects.equals(item.getOrganizationId(), signal.getOrganizationId()))
				.toList();
		Set<Long> clients = window.stream().map(IncidentSignal::getClientId).collect(Collectors.toCollection(LinkedHashSet::new));
		clients.add(signal.getClientId());
		Set<Long> organizations = window.stream().map(IncidentSignal::getOrganizationId).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (signal.getOrganizationId() != null) {
			organizations.add(signal.getOrganizationId());
		}
		return new WindowCount(window, clients, organizations, windowStart);
	}


	// ======================================================================
	// 3. Пауза после срабатывания
	// ======================================================================

	/**
	 * @param incidentId активный инцидент, созданный недавним срабатыванием по тому же сервису и симптому
	 * @param active     недавнее срабатывание было
	 */
	public record Cooldown(Long incidentId, boolean active) {
	}


	/**
	 * Было ли за последние {@code minutes} срабатывание по сервису и симптому сигнала. С {@code onlyWithIncident}
	 * считаются только срабатывания, по которым создан инцидент: срабатывания «только уведомить» не должны
	 * блокировать создание после смены схемы правила.
	 * <p>
	 * Срабатывание, чей инцидент закрыт, паузу не держит: проблему решили, и повторные обращения — новый сбой.
	 * Отменённый инцидент паузу держит: оператор счёл срабатывание ложным, и тот же сигнал не должен сразу создать его снова.
	 */
	public Cooldown cooldown(IncidentSignal signal, int minutes, boolean onlyWithIncident) {
		if (minutes <= 0) {
			return new Cooldown(null, false);
		}
		ZonedDateTime since = signal.getCreatedAt().minusMinutes(minutes);
		List<IncidentSignal> triggered = signalRepository.findAllByServiceIdAndSymptomAndDecisionAndCreatedAtAfterOrderByCreatedAtDesc(
				signal.getServiceId(), signal.getSymptom(), IncidentSignalDecision.TRIGGERED, since);
		Map<Long, Optional<SupportIncident>> incidents = new HashMap<>();
		Long activeIncidentId = null;
		boolean active = false;
		for (IncidentSignal trigger : triggered) {
			Long incidentId = trigger.getMatchedIncidentId();
			if (incidentId == null) {
				active |= !onlyWithIncident;
				continue;
			}
			Optional<SupportIncident> incident = incidents.computeIfAbsent(incidentId, incidentRepository::findById);
			IncidentStatus status = incident.map(SupportIncident::getStatus).orElse(IncidentStatus.CLOSED);
			if (status == IncidentStatus.CLOSED) {
				continue;
			}
			active = true;
			if (activeIncidentId == null && status.isActive()) {
				activeIncidentId = incidentId;
			}
		}
		return new Cooldown(activeIncidentId, active);
	}


	// ======================================================================
	// 4. Проверка пачки
	// ======================================================================

	/** Jev проверяет, что сообщения окна и текущее — одна проблема; тексты берутся из message по message_id. */
	public Optional<JevService.BatchVerdict> verifyBatch(String context, List<Long> windowSignalIds, String currentText) {
		List<IncidentSignal> window = signalRepository.findAllById(windowSignalIds).stream()
				.sorted(Comparator.comparing(IncidentSignal::getCreatedAt))
				.toList();
		Optional<JevService.BatchVerdict> verdict = jevService.verifyBatch(context, batchTexts(window, currentText));
		verdict.ifPresent(item -> decisionRecorder.record(context, "same_problem", "оценка «одна проблема»",
				item.sameProblemProbability(), null, JevDecisionRecorder.Outcome.UNDECIDED));
		return verdict;
	}


	// ======================================================================
	// 5. Действия по сигналу
	// ======================================================================

	/** Записывает решение по сигналу; с {@code incidentId} — ещё и инцидент, к которому он относится. */
	public void mark(IncidentSignal signal, IncidentSignalDecision decision, Long incidentId) {
		signal.setDecision(decision);
		if (incidentId != null) {
			signal.setMatchedIncidentId(incidentId);
		}
		signalRepository.save(signal);
	}


	/**
	 * Относит сообщение к открытому инциденту: привязывает заявку клиента и пишет запись в таймлайн.
	 * Заявка ищется по сообщению, иначе — последняя открытая заявка клиента за {@code lookbackMinutes}.
	 */
	public void attach(String context, IncidentSignal signal, Long incidentId, int lookbackMinutes) {
		SupportIncident incident = incidentRepository.findById(incidentId).orElse(null);
		if (incident == null) {
			log.warn("[{}] incident detector: incident #{} not found, signal not attached", context, incidentId);
			return;
		}
		mark(signal, IncidentSignalDecision.MATCHED_INCIDENT, incident.getId());
		Optional<Task> task = findTask(signal.getClientId(), signal.getMessageId(),
				signal.getCreatedAt().minusMinutes(lookbackMinutes));
		List<Long> linked = task.map(t -> incidentService.linkTasksForSystem(incident.getId(), List.of(t.getId())))
				.orElse(List.of());
		incidentService.addSystemNote(incident.getId(), "Ещё одно похожее обращение: клиент #%d, сообщение #%d%s".formatted(
				signal.getClientId(), signal.getMessageId(),
				linked.isEmpty() ? "" : ", заявка #" + linked.getFirst()));
		log.debug("[{}] incident detector: matches {} «{}» -> {}", context, incident.getIncidentNumber(),
				incident.getTitle(),
				linked.isEmpty()
						? task.map(t -> "task #" + t.getId() + " already linked to an incident").orElse("no task to link yet")
						: "linked task #" + linked.getFirst());
	}


	/**
	 * Создаёт черновик инцидента по пачке сигналов и привязывает к нему заявки. {@code updateIntervals} —
	 * интервал обновлений инцидента по серьёзности, минут (нет значения — по умолчанию в IncidentService).
	 */
	public SupportIncident createDraft(
			String context,
			IncidentSignal signal,
			List<IncidentSignal> window,
			JevService.BatchVerdict verdict,
			IncidentSeverity severity,
			int windowMinutes,
			Map<IncidentSeverity, Integer> updateIntervals
	) {
		SystemDraft draft = buildDraft(signal, window, verdict, severity, windowMinutes, updateIntervals);
		SupportIncident incident = incidentService.createDraftForSystem(draft.draft());
		mark(signal, IncidentSignalDecision.TRIGGERED, incident.getId());
		for (IncidentSignal previous : window) {
			if (previous.getMatchedIncidentId() == null) {
				previous.setMatchedIncidentId(incident.getId());
				signalRepository.save(previous);
			}
		}
		log.debug("[{}] incident detector TRIGGERED: severity={}{}, cause={} -> created draft {} «{}», "
						+ "tasks linked: {}, leader={}, line={}, messages {}",
				context, severity, severityNote(verdict.severity(), severity), verdict.probableCause(),
				incident.getIncidentNumber(), incident.getTitle(), draft.draft().taskIds(), draft.leaderName(),
				draft.lineName(), draft.messageIds());
		return incident;
	}


	/**
	 * Уведомление через стандартный механизм (колокольчик и история уведомлений) владельцу и исполнителям сервиса,
	 * ответственному и участникам линии; если таких нет — администраторам.
	 * Отправляется после коммита, чтобы сбой доставки не откатывал созданный инцидент.
	 */
	public void notifyResponsible(String context, IncidentSignal signal, List<IncidentSignal> window, int lookbackMinutes,
	                              String message) {
		ru.ravel.ItDesk.model.Service service = serviceRepository.findById(signal.getServiceId()).orElse(null);
		if (service == null) {
			log.warn("[{}] incident detector: service #{} not found, nobody notified", context, signal.getServiceId());
			return;
		}
		List<IncidentSignal> batch = batchOf(signal, window);
		ZonedDateTime windowStart = signal.getCreatedAt().minusMinutes(lookbackMinutes);
		List<Task> tasks = tasksOf(batch, windowStart);
		List<Long> recipients = recipients(service, supportLine(service, tasks));
		notifyRecipients(context, recipients, message);
		log.debug("[{}] incident detector: notified {} user(s)", context, recipients.size());
	}


	// ======================================================================

	static IncidentSeverity severityOf(String value) {
		try {
			return IncidentSeverity.valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (RuntimeException e) {
			throw new IllegalArgumentException("Неизвестная серьёзность инцидента: " + value + " (допустимы P1, P2, P3, P4)");
		}
	}


	private String severityNote(IncidentSeverity jevSeverity, IncidentSeverity applied) {
		return jevSeverity == applied ? "" : " (Jev: " + jevSeverity + ", изменена правилом)";
	}


	private record SystemDraft(
			IncidentService.SystemIncidentDraft draft,
			List<Long> messageIds,
			String leaderName,
			String lineName
	) {
	}


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


	private List<IncidentSignal> batchOf(IncidentSignal signal, List<IncidentSignal> window) {
		List<IncidentSignal> batch = new ArrayList<>(window);
		batch.add(signal);
		return batch;
	}


	private List<Task> tasksOf(List<IncidentSignal> batch, ZonedDateTime since) {
		return batch.stream()
				.map(item -> findTask(item.getClientId(), item.getMessageId(), since))
				.flatMap(Optional::stream)
				.collect(Collectors.toMap(Task::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new))
				.values().stream().toList();
	}


	/** Линия по умолчанию у сервиса; иначе самая частая линия среди заявок пачки. */
	private SupportLine supportLine(ru.ravel.ItDesk.model.Service service, List<Task> tasks) {
		SupportLine line = service.getDefaultSupportLine();
		if (line != null) {
			return line;
		}
		return tasks.stream()
				.map(Task::getSupportLine)
				.filter(Objects::nonNull)
				.collect(Collectors.groupingBy(SupportLine::getId, LinkedHashMap::new, Collectors.toList()))
				.values().stream()
				.max(Comparator.comparingInt(List::size))
				.map(List::getFirst)
				.orElse(null);
	}


	private SystemDraft buildDraft(
			IncidentSignal signal,
			List<IncidentSignal> window,
			JevService.BatchVerdict verdict,
			IncidentSeverity severity,
			int windowMinutes,
			Map<IncidentSeverity, Integer> updateIntervals
	) {
		ru.ravel.ItDesk.model.Service service = serviceRepository.findById(signal.getServiceId())
				.orElseThrow(() -> new IllegalStateException("Сервис сигнала не найден: " + signal.getServiceId()));
		List<IncidentSignal> batch = batchOf(signal, window);
		Set<Long> clients = batch.stream().map(IncidentSignal::getClientId).collect(Collectors.toCollection(LinkedHashSet::new));
		Set<Long> organizations = batch.stream().map(IncidentSignal::getOrganizationId).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		ServiceMonitoringStatus monitoring = service.getMonitoringStatus();
		ZonedDateTime windowStart = signal.getCreatedAt().minusMinutes(windowMinutes);
		String symptom = signal.getSymptom();
		String symptomDescription = JevService.SYMPTOMS.getOrDefault(symptom, symptom);
		String cause = verdict.probableCause();
		List<Long> messageIds = batch.stream().map(IncidentSignal::getMessageId).toList();
		ZonedDateTime startedAt = batch.stream().map(IncidentSignal::getCreatedAt).min(Comparator.naturalOrder()).orElse(windowStart);

		List<Task> tasks = tasksOf(batch, windowStart);
		SupportLine line = supportLine(service, tasks);
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
				.append(" за ").append(windowMinutes).append(" мин; первое — ")
				.append(TIME.format(startedAt.withZoneSameInstant(zone))).append(".\n")
				.append("Проверка пачки: одна проблема с вероятностью ").append(format(verdict.sameProblemProbability()))
				.append(", серьёзность по оценке Jev — ").append(verdict.severity())
				.append(" (оценка ").append(format(verdict.severityScore())).append(" из 3)");
		if (verdict.severity() != severity) {
			description.append(", снижена до ").append(severity).append(" правилом детектора");
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
				updateIntervals == null ? null : updateIntervals.get(severity)
		);
		return new SystemDraft(
				draft,
				messageIds,
				owner == null ? "-" : Objects.toString(owner.getUsername(), "#" + owner.getId()),
				line == null ? "-" : line.getName()
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

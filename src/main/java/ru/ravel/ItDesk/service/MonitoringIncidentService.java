package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.dto.MonitoringProblemEvent;
import ru.ravel.ItDesk.model.MonitoringProblem;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.MonitoringProblemRepository;
import ru.ravel.ItDesk.repository.OrganizationServiceAssignmentRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;


/**
 * Инциденты по событиям мониторинга Zabbix (действие автоматизации incident.fromMonitoring()).
 * Проблема по сервису создаёт черновик инцидента, а если по сервису уже есть активный инцидент —
 * привязывается к нему заметкой. Когда открытых проблем по сервису не осталось, инцидент переводится
 * к проверке восстановления.
 */
@Service
@RequiredArgsConstructor
public class MonitoringIncidentService {

	private static final Logger log = LoggerFactory.getLogger(MonitoringIncidentService.class);
	private static final String SOURCE = "мониторинг Zabbix";
	private static final Set<IncidentStatus> INACTIVE = EnumSet.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED);
	private static final Map<IncidentSeverity, Integer> UPDATE_INTERVALS = Map.of(
			IncidentSeverity.P1, 15,
			IncidentSeverity.P2, 30,
			IncidentSeverity.P3, 60,
			IncidentSeverity.P4, 120
	);

	private final IncidentRepository incidentRepository;
	private final IncidentService incidentService;
	private final MonitoringProblemRepository problemRepository;
	private final ServiceRepository serviceRepository;
	private final OrganizationServiceAssignmentRepository assignmentRepository;
	private final ClientRepository clientRepository;
	private static final int P1_MIN_CLIENTS = 10;
	private final UserNotificationService userNotificationService;
	private final UserRepository userRepository;
	private final AfterCommitExecutor afterCommitExecutor;


	/**
	 * Что сделало действие.
	 */
	public enum Outcome {
		CREATED,
		LINKED,
		RECOVERY_CHECK,
		NOTE,
		SKIPPED
	}


	public record Result(Outcome outcome, Long incidentId, String details) {

		static Result skipped(String details) {
			return new Result(Outcome.SKIPPED, null, details);
		}
	}


	@Transactional
	public Result handle(TriggerType trigger, MonitoringProblemEvent event) {
		if (event == null || event.problem() == null) {
			return Result.skipped("в событии нет проблемы мониторинга");
		}
		if (event.service() == null || event.service().id() == null) {
			return Result.skipped("проблема не сопоставлена с сервисом");
		}
		Optional<ru.ravel.ItDesk.model.Service> service = serviceRepository.findById(event.service().id());
		if (service.isEmpty()) {
			return Result.skipped("сервис #" + event.service().id() + " не найден");
		}
		return switch (trigger) {
			case MONITORING_PROBLEM_STARTED -> onProblem(service.get(), event.problem());
			case MONITORING_PROBLEM_UPDATED -> onUpdate(service.get(), event.problem());
			case MONITORING_PROBLEM_RESOLVED -> onResolved(service.get(), event.problem());
			default -> Result.skipped("действие работает только на событиях мониторинга, а не на " + trigger);
		};
	}


	private Result onProblem(ru.ravel.ItDesk.model.Service service, MonitoringProblemEvent.Problem problem) {
		Optional<SupportIncident> active = activeIncident(service);
		if (active.isPresent()) {
			incidentService.addSystemNote(active.get().getId(), "Мониторинг: новая проблема по сервису «%s» — %s"
					.formatted(service.getName(), describe(problem)));
			return new Result(Outcome.LINKED, active.get().getId(), "проблема привязана к " + active.get().getIncidentNumber());
		}
		IncidentSeverity severity = severity(service, problem.severity());
		SupportLine line = service.getDefaultSupportLine();
		User owner = service.getOwner();
		List<Long> participants = service.getExecutors() == null ? List.of() : service.getExecutors().stream()
				.map(User::getId)
				.filter(Objects::nonNull)
				.filter(id -> owner == null || !id.equals(owner.getId()))
				.toList();
		String title = "Мониторинг: %s — %s".formatted(service.getName(), Objects.toString(problem.name(), "проблема"));
		String description = "Создан автоматически по событию Zabbix.\n"
				+ "Сервис: " + service.getName() + ".\n"
				+ "Проблема: " + describe(problem) + ".\n"
				+ (problem.url() == null ? "" : "Событие в Zabbix: " + problem.url() + "\n")
				+ "Серьёзность " + severity + " по важности Zabbix " + Objects.toString(problem.severity(), "—")
				+ (severityCapped(service, problem.severity()) ? " (P1 только при затронутых клиентах от "
				+ P1_MIN_CLIENTS + ")" : "") + ".\n"
				+ "Проверьте данные и переведите инцидент в «Проверка» или «Объявлен».";
		String publicDescription = "Наблюдаются проблемы с сервисом «%s». Мы уже разбираемся и сообщим, как только появятся новости."
				.formatted(service.getName());
		SupportIncident incident = incidentService.createDraftForSystem(new IncidentService.SystemIncidentDraft(
				title,
				description,
				publicDescription,
				"По данным мониторинга: " + Objects.toString(problem.name(), "проблема") + ".",
				severity,
				service.getId(),
				line == null ? null : line.getId(),
				owner == null ? null : owner.getId(),
				owner == null ? null : owner.getId(),
				participants,
				List.of(),
				List.of(),
				startedAt(problem),
				UPDATE_INTERVALS.get(severity)
		), SOURCE);
		notifyRecipients(service, line, "Мониторинг: создан черновик инцидента %s «%s»"
				.formatted(incident.getIncidentNumber(), title));
		log.info("monitoring incident: draft {} for service «{}» (Zabbix event {}, severity {})",
				incident.getIncidentNumber(), service.getCode(), problem.eventId(), severity);
		return new Result(Outcome.CREATED, incident.getId(), "создан черновик " + incident.getIncidentNumber());
	}


	private Result onUpdate(ru.ravel.ItDesk.model.Service service, MonitoringProblemEvent.Problem problem) {
		Optional<SupportIncident> active = activeIncident(service);
		if (active.isEmpty()) {
			// Начало проблемы могло не дойти: открытая проблема без инцидента обрабатывается как новая
			return "OPEN".equals(problem.status())
					? onProblem(service, problem)
					: Result.skipped("по сервису нет активного инцидента");
		}
		String details = Optional.ofNullable(problem.updateMessage())
				.or(() -> Optional.ofNullable(problem.updateAction()))
				.orElse("проблема обновлена");
		incidentService.addSystemNote(active.get().getId(), "Мониторинг: %s — %s%s".formatted(
				describe(problem), details, problem.updateUser() == null ? "" : " (" + problem.updateUser() + ")"));
		return new Result(Outcome.NOTE, active.get().getId(), "заметка в " + active.get().getIncidentNumber());
	}


	private Result onResolved(ru.ravel.ItDesk.model.Service service, MonitoringProblemEvent.Problem problem) {
		Optional<SupportIncident> active = activeIncident(service);
		if (active.isEmpty()) {
			return Result.skipped("по сервису нет активного инцидента");
		}
		Long incidentId = active.get().getId();
		List<MonitoringProblem> open = problemRepository
				.findAllByServiceIdAndStatusOrderBySeverityDescStartedAtDesc(service.getId(), MonitoringProblem.Status.OPEN);
		if (!open.isEmpty()) {
			incidentService.addSystemNote(incidentId, "Мониторинг: устранена проблема — %s. Открытых проблем по сервису: %d"
					.formatted(describe(problem), open.size()));
			return new Result(Outcome.NOTE, incidentId, "открытых проблем ещё " + open.size());
		}
		IncidentService.RecoveryCheckResult result = incidentService.moveToRecoveryCheckForSystem(incidentId,
				"Мониторинг: все проблемы по сервису «%s» устранены (последняя — %s)".formatted(service.getName(), describe(problem)));
		return result == IncidentService.RecoveryCheckResult.MOVED
				? new Result(Outcome.RECOVERY_CHECK, incidentId, "переведён в «Наблюдение»")
				: new Result(Outcome.NOTE, incidentId, "статус не изменён: " + active.get().getStatus());
	}


	private Optional<SupportIncident> activeIncident(ru.ravel.ItDesk.model.Service service) {
		return incidentRepository.findFirstByServicesIdAndStatusNotInOrderByIdDesc(service.getId(), INACTIVE);
	}


	/**
	 * Важность Zabbix → серьёзность: 5 Disaster — P1, 4 High — P2, 3 Average — P3, иначе P4.
	 * P1 — только если клиентов организаций с этим сервисом не меньше порога P1 детектора: без завышения.
	 */
	IncidentSeverity severity(ru.ravel.ItDesk.model.Service service, Integer zabbixSeverity) {
		int value = zabbixSeverity == null ? 0 : zabbixSeverity;
		IncidentSeverity severity = value >= 5 ? IncidentSeverity.P1
				: value == 4 ? IncidentSeverity.P2
				: value == 3 ? IncidentSeverity.P3
				: IncidentSeverity.P4;
		return severity == IncidentSeverity.P1 && severityCapped(service, value) ? IncidentSeverity.P2 : severity;
	}


	private boolean severityCapped(ru.ravel.ItDesk.model.Service service, Integer zabbixSeverity) {
		if (zabbixSeverity == null || zabbixSeverity < 5) {
			return false;
		}
		return affectedClients(service) < P1_MIN_CLIENTS;
	}


	private long affectedClients(ru.ravel.ItDesk.model.Service service) {
		Set<Long> organizations = new HashSet<>();
		assignmentRepository.findAllByServiceIdOrderByOrganizationNameAsc(service.getId()).stream()
				.filter(assignment -> assignment.isEffectiveOn(null))
				.filter(assignment -> assignment.getOrganization() != null && assignment.getOrganization().getId() != null)
				.forEach(assignment -> organizations.add(assignment.getOrganization().getId()));
		return organizations.isEmpty() ? 0 : clientRepository.countByOrganizationIdIn(organizations);
	}


	private ZonedDateTime startedAt(MonitoringProblemEvent.Problem problem) {
		try {
			return problem.startedAt() == null ? ZonedDateTime.now() : OffsetDateTime.parse(problem.startedAt()).toZonedDateTime();
		} catch (Exception e) {
			return ZonedDateTime.now();
		}
	}


	private String describe(MonitoringProblemEvent.Problem problem) {
		StringBuilder text = new StringBuilder(Objects.toString(problem.name(), "проблема"));
		if (problem.hostName() != null || problem.host() != null) {
			text.append(" на ").append(problem.hostName() != null ? problem.hostName() : problem.host());
		}
		if (problem.severityName() != null) {
			text.append(" (").append(problem.severityName()).append(")");
		}
		return text.append(", событие ").append(problem.eventId()).toString();
	}


	/**
	 * Владелец и исполнители сервиса, ответственный и участники линии; если никого нет — администраторы.
	 * Отправка после коммита: черновик уже виден получателю.
	 */
	private void notifyRecipients(ru.ravel.ItDesk.model.Service service, SupportLine line, String message) {
		LinkedHashSet<User> users = new LinkedHashSet<>();
		if (service.getOwner() != null) users.add(service.getOwner());
		if (service.getExecutors() != null) users.addAll(service.getExecutors());
		if (line != null) {
			if (line.getResponsible() != null) users.add(line.getResponsible());
			if (line.getMembers() != null) users.addAll(line.getMembers());
		}
		List<Long> ids = users.stream().filter(User::isEnabled).map(User::getId).filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			ids = userRepository.findAll().stream()
					.filter(User::isEnabled)
					.filter(user -> user.getAuthorities() != null && user.getAuthorities().contains(Role.ADMIN))
					.map(User::getId)
					.filter(Objects::nonNull)
					.toList();
		}
		for (Long userId : ids) {
			afterCommitExecutor.run(() -> userNotificationService.send(
					new UserNotification(UserNotificationEvent.INCIDENT_DETECTED, message, userId)));
		}
	}
}

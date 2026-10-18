package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.incident.*;
import ru.ravel.ItDesk.repository.*;

import java.text.Normalizer;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class IncidentService {

	private final IncidentRepository incidentRepository;
	private final IncidentTaskLinkRepository incidentTaskLinkRepository;
	private final IncidentTimelineEntryRepository incidentTimelineEntryRepository;
	private final IncidentUpdateTemplateRepository incidentUpdateTemplateRepository;
	private final TaskRepository taskRepository;
	private final ClientRepository clientRepository;
	private final OrganizationRepository organizationRepository;
	private final UserRepository userRepository;
	private final SupportLineRepository supportLineRepository;
	private final ServiceRepository serviceRepository;
	private final UserService userService;
	private final UserNotificationService userNotificationService;
	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final ru.ravel.ItDesk.component.AfterCommitExecutor afterCommitExecutor;

	@Transactional(readOnly = true)
	public Map<String, Object> getPage(IncidentPageRequest request) {
		IncidentPageRequest safeRequest = request == null ? new IncidentPageRequest() : request;
		int page = Math.max(1, Objects.requireNonNullElse(safeRequest.getPage(), 1));
		int size = Math.max(1, Math.min(100, Objects.requireNonNullElse(safeRequest.getSize(), 30)));
		String search = normalizeText(safeRequest.getSearch());
		boolean includeClosed = Boolean.TRUE.equals(safeRequest.getIncludeClosed());
		boolean overdueOnly = Boolean.TRUE.equals(safeRequest.getOverdueOnly());
		Set<IncidentSeverity> severities = parseEnumSet(safeRequest.getSeverities(), IncidentSeverity.class);
		Set<IncidentStatus> statuses = parseEnumSet(safeRequest.getStatuses(), IncidentStatus.class);
		Set<Long> organizationIds = safeLongSet(safeRequest.getOrganizationIds());
		Set<Long> serviceIds = safeLongSet(safeRequest.getServiceIds());
		Set<Long> leaderIds = safeLongSet(safeRequest.getLeaderIds());

		User currentUser = userService.getCurrentUser();
		List<SupportIncident> filtered = incidentRepository.findAll().stream()
				.filter(incident -> canUserView(incident, currentUser))
				.filter(incident -> includeClosed || incident.getStatus().isActive())
				.filter(incident -> severities.isEmpty() || severities.contains(incident.getSeverity()))
				.filter(incident -> statuses.isEmpty() || statuses.contains(incident.getStatus()))
				.filter(incident -> organizationIds.isEmpty() || incident.getOrganizations().stream()
						.map(Organization::getId)
						.anyMatch(organizationIds::contains))
				.filter(incident -> serviceIds.isEmpty() || incident.getServices().stream()
						.map(ru.ravel.ItDesk.model.Service::getId)
						.anyMatch(serviceIds::contains))
				.filter(incident -> leaderIds.isEmpty() || incident.getLeader() != null && leaderIds.contains(incident.getLeader().getId()))
				.filter(incident -> !overdueOnly || isCommunicationOverdue(incident))
				.filter(incident -> search.isBlank() || matchesSearch(incident, search))
				.sorted(Comparator
						.comparing((SupportIncident incident) -> severityRank(incident.getSeverity()))
						.thenComparing(incident -> Objects.requireNonNullElse(incident.getUpdatedAt(), incident.getCreatedAt()), Comparator.reverseOrder())
						.thenComparing(SupportIncident::getId, Comparator.reverseOrder()))
				.toList();

		int from = Math.min(filtered.size(), (page - 1) * size);
		int to = Math.min(filtered.size(), from + size);
		List<SupportIncident> pageItems = filtered.subList(from, to);
		Map<Long, IncidentTaskLinkRepository.IncidentTaskStats> taskStats = loadTaskStats(pageItems);
		List<Map<String, Object>> rows = pageItems.stream()
				.map(incident -> toSummaryDto(incident, taskStats.get(incident.getId())))
				.toList();

		Map<String, Object> response = new LinkedHashMap<>();
		response.put("incidents", rows);
		response.put("page", page);
		response.put("size", size);
		response.put("totalElements", filtered.size());
		response.put("totalPages", filtered.isEmpty() ? 0 : (int) Math.ceil((double) filtered.size() / size));
		response.put("activeCount", filtered.stream().filter(incident -> incident.getStatus().isActive()).count());
		response.put("criticalCount", filtered.stream().filter(incident -> incident.getStatus().isActive())
				.filter(incident -> incident.getSeverity() == IncidentSeverity.P1 || incident.getSeverity() == IncidentSeverity.P2)
				.count());
		return response;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> getIncident(Long incidentId) {
		SupportIncident incident = getAccessibleIncident(incidentId);
		return toDetailDto(incident);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> getPortalActiveIncidents() {
		Client client = getCurrentPortalClient();
		return incidentRepository.findAllByStatusNotIn(List.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED)).stream()
				.filter(this::isVisibleInPortal)
				.filter(incident -> affectsClient(incident, client))
				.sorted(Comparator
						.comparing((SupportIncident incident) -> severityRank(incident.getSeverity()))
						.thenComparing(incident -> Objects.requireNonNullElse(incident.getUpdatedAt(), incident.getCreatedAt()), Comparator.reverseOrder()))
				.map(incident -> toPortalDto(incident, client))
				.toList();
	}

	@Transactional
	public Map<String, Object> confirmPortalRecovery(Long incidentId, IncidentRecoveryConfirmationRequest request) {
		Client client = getCurrentPortalClient();
		SupportIncident incident = incidentRepository.findById(incidentId)
				.orElseThrow(() -> new IllegalArgumentException("Инцидент не найден"));
		if (!affectsClient(incident, client)) {
			throw new AccessDeniedException("Инцидент не относится к текущему клиенту");
		}
		boolean confirmed = request == null || Boolean.TRUE.equals(request.getConfirmed());
		User currentUser = userService.getCurrentUser();
		ZonedDateTime now = ZonedDateTime.now();
		List<IncidentTaskLink> clientLinks = incidentTaskLinkRepository
				.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incidentId).stream()
				.filter(link -> {
					Client linkedClient = clientRepository.findByTaskId(link.getTask().getId()).orElse(null);
					return linkedClient != null && Objects.equals(linkedClient.getId(), client.getId());
				})
				.toList();
		if (clientLinks.isEmpty()) {
			throw new IllegalStateException("Для подтверждения нет связанной заявки текущего клиента");
		}
		for (IncidentTaskLink link : clientLinks) {
			link.setRecoveryConfirmed(confirmed);
			link.setRecoveryConfirmedAt(confirmed ? now : null);
			link.setRecoveryConfirmedBy(confirmed ? currentUser : null);
		}
		incidentTaskLinkRepository.saveAll(clientLinks);
		incidentTimelineEntryRepository.save(IncidentTimelineEntry.builder()
				.incident(incident)
				.type(IncidentTimelineType.RECOVERY_CONFIRMATION)
				.audience(IncidentTimelineAudience.INTERNAL)
				.text("Клиент %s %s восстановление".formatted(
						clientDisplayName(client),
						confirmed ? "подтвердил" : "отозвал подтверждение"
				))
				.author(currentUser)
				.createdAt(now)
				.build());
		publishIncidentChanged(incident.getId());
		return toPortalDto(incident, client);
	}

	@Transactional
	public Map<String, Object> createIncident(IncidentSaveRequest request) {
		if (request == null || request.getTitle() == null || request.getTitle().isBlank()) {
			throw new IllegalArgumentException("Название инцидента обязательно");
		}
		User currentUser = userService.getCurrentUser();
		SupportIncident incident = SupportIncident.builder()
				.title(request.getTitle().trim())
				.createdBy(currentUser)
				.createdAt(ZonedDateTime.now())
				.updatedAt(ZonedDateTime.now())
				.build();
		applyRequest(incident, request);
		validateIncident(incident);
		SupportIncident saved = incidentRepository.saveAndFlush(incident);
		saved.setIncidentNumber("INC-%06d".formatted(saved.getId()));
		saved = incidentRepository.saveAndFlush(saved);
		saveSystemEntry(saved, "Инцидент %s создан".formatted(saved.getIncidentNumber()));
		if (request.getTaskIds() != null && !request.getTaskIds().isEmpty()) {
			linkTasksInternal(saved, request.getTaskIds(), request.getPrimaryTaskId(), currentUser);
		}
		return changed(saved);
	}

	@Transactional
	public Map<String, Object> createFromTasks(IncidentSaveRequest request) {
		if (request == null || request.getTaskIds() == null || request.getTaskIds().isEmpty()) {
			throw new IllegalArgumentException("Не выбраны заявки для инцидента");
		}
		if (request.getServiceIds() == null || request.getServiceIds().isEmpty()) {
			request.setServiceIds(request.getTaskIds().stream()
					.map(taskRepository::findById)
					.flatMap(Optional::stream)
					.map(Task::getService)
					.filter(Objects::nonNull)
					.map(ru.ravel.ItDesk.model.Service::getId)
					.filter(Objects::nonNull)
					.distinct()
					.toList());
		}
		if (request.getTitle() == null || request.getTitle().isBlank()) {
			Task firstTask = taskRepository.findById(request.getTaskIds().getFirst()).orElseThrow();
			request.setTitle("Инцидент: " + Objects.toString(firstTask.getName(), "массовая проблема"));
		}
		return createIncident(request);
	}

	@Transactional
	public Map<String, Object> updateIncident(Long incidentId, IncidentSaveRequest request) {
		SupportIncident incident = getAccessibleIncidentForChange(incidentId);
		IncidentStatus oldStatus = incident.getStatus();
		IncidentSeverity oldSeverity = incident.getSeverity();
		Long oldLeaderId = idOf(incident.getLeader());
		applyRequest(incident, request);
		validateIncident(incident);
		incident.setUpdatedAt(ZonedDateTime.now());
		SupportIncident saved = incidentRepository.saveAndFlush(incident);

		if (oldStatus != saved.getStatus()) {
			saveSystemEntry(saved, "Статус изменён: %s → %s".formatted(oldStatus, saved.getStatus()));
		}
		if (oldSeverity != saved.getSeverity()) {
			saveSystemEntry(saved, "Критичность изменена: %s → %s".formatted(oldSeverity, saved.getSeverity()));
		}
		if (!Objects.equals(oldLeaderId, idOf(saved.getLeader()))) {
			saveSystemEntry(saved, saved.getLeader() == null
					? "Руководитель инцидента снят"
					: "Руководителем инцидента назначен %s".formatted(userDisplayName(saved.getLeader())));
		}
		return changed(saved);
	}

	@Transactional
	public Map<String, Object> addTimelineEntry(Long incidentId, IncidentTimelineRequest request) {
		SupportIncident incident = getAccessibleIncidentForChange(incidentId);
		if (request == null || request.getText() == null || request.getText().isBlank()) {
			throw new IllegalArgumentException("Текст записи обязателен");
		}
		IncidentTimelineAudience audience = parseEnum(request.getAudience(), IncidentTimelineAudience.class, IncidentTimelineAudience.INTERNAL);
		IncidentTimelineType type = parseEnum(request.getType(), IncidentTimelineType.class,
				audience == IncidentTimelineAudience.CLIENTS || audience == IncidentTimelineAudience.PUBLIC
						? IncidentTimelineType.CLIENT_UPDATE
						: IncidentTimelineType.NOTE);
		ZonedDateTime now = ZonedDateTime.now();
		IncidentTimelineEntry entry = IncidentTimelineEntry.builder()
				.incident(incident)
				.type(type)
				.audience(audience)
				.text(request.getText().trim())
				.author(currentUserOrNull())
				.createdAt(now)
				.templateKey(blankToNull(request.getTemplateKey()))
				.build();
		incidentTimelineEntryRepository.save(entry);

		if (audience == IncidentTimelineAudience.CLIENTS || audience == IncidentTimelineAudience.PUBLIC) {
			publishUpdateToClients(incident, request.getText().trim());
		}

		if (audience == IncidentTimelineAudience.OPERATORS || audience == IncidentTimelineAudience.CLIENTS || audience == IncidentTimelineAudience.PUBLIC) {
			registerPublishedUpdate(incident, now, request.getNextUpdateAt());
		} else if (request.getNextUpdateAt() != null) {
			incident.setNextUpdateAt(request.getNextUpdateAt());
			incident.setCommunicationOverdueNotifiedAt(null);
		}
		incident.setUpdatedAt(now);
		incidentRepository.save(incident);
		return changed(incident);
	}

	@Transactional
	public Map<String, Object> linkTasks(Long incidentId, IncidentTaskIdsRequest request) {
		SupportIncident incident = getAccessibleIncidentForChange(incidentId);
		List<Long> taskIds = request == null ? List.of() : Objects.requireNonNullElse(request.getTaskIds(), List.of());
		linkTasksInternal(incident, taskIds, request == null ? null : request.getPrimaryTaskId(), userService.getCurrentUser());
		return changed(incident);
	}

	@Transactional
	public Map<String, Object> unlinkTask(Long incidentId, Long taskId) {
		SupportIncident incident = getAccessibleIncidentForChange(incidentId);
		IncidentTaskLink link = incidentTaskLinkRepository.findFirstByIncidentIdAndTaskIdAndActiveTrue(incidentId, taskId)
				.orElseThrow(() -> new IllegalArgumentException("Заявка не связана с инцидентом"));
		link.setActive(false);
		link.setUnlinkedAt(ZonedDateTime.now());
		link.setUnlinkedBy(userService.getCurrentUser());
		incidentTaskLinkRepository.save(link);
		incident.setUpdatedAt(ZonedDateTime.now());
		incidentRepository.save(incident);
		saveSystemEntry(incident, "Заявка #%d отвязана от инцидента".formatted(taskId));
		return changed(incident);
	}

	@Transactional
	public Map<String, Object> setRecoveryConfirmation(Long incidentId, Long taskId, IncidentRecoveryConfirmationRequest request) {
		SupportIncident incident = getAccessibleIncidentForChange(incidentId);
		IncidentTaskLink link = incidentTaskLinkRepository.findFirstByIncidentIdAndTaskIdAndActiveTrue(incidentId, taskId)
				.orElseThrow(() -> new IllegalArgumentException("Заявка не связана с инцидентом"));
		boolean confirmed = request != null && Boolean.TRUE.equals(request.getConfirmed());
		link.setRecoveryConfirmed(confirmed);
		link.setRecoveryConfirmedAt(confirmed ? ZonedDateTime.now() : null);
		link.setRecoveryConfirmedBy(confirmed ? userService.getCurrentUser() : null);
		incidentTaskLinkRepository.save(link);
		incidentTimelineEntryRepository.save(IncidentTimelineEntry.builder()
				.incident(incident)
				.type(IncidentTimelineType.RECOVERY_CONFIRMATION)
				.audience(IncidentTimelineAudience.INTERNAL)
				.text("По заявке #%d восстановление %s".formatted(taskId, confirmed ? "подтверждено" : "снято"))
				.author(userService.getCurrentUser())
				.build());
		return changed(incident);
	}

	@Transactional
	public Map<String, Object> bulkTaskOperation(Long incidentId, IncidentBulkTaskRequest request) {
		SupportIncident incident = getAccessibleIncidentForChange(incidentId);
		List<Long> taskIds = request == null ? List.of() : Objects.requireNonNullElse(request.getTaskIds(), List.of());
		String operation = request == null ? "" : Objects.toString(request.getOperation(), "").trim().toUpperCase(Locale.ROOT);
		for (Long taskId : taskIds) {
			IncidentTaskLink link = incidentTaskLinkRepository.findFirstByIncidentIdAndTaskIdAndActiveTrue(incidentId, taskId).orElse(null);
			if (link == null) {
				continue;
			}
			switch (operation) {
				case "CONFIRM_RECOVERY" -> {
					link.setRecoveryConfirmed(true);
					link.setRecoveryConfirmedAt(ZonedDateTime.now());
					link.setRecoveryConfirmedBy(userService.getCurrentUser());
				}
				case "UNCONFIRM_RECOVERY" -> {
					link.setRecoveryConfirmed(false);
					link.setRecoveryConfirmedAt(null);
					link.setRecoveryConfirmedBy(null);
				}
				case "UNLINK" -> {
					link.setActive(false);
					link.setUnlinkedAt(ZonedDateTime.now());
					link.setUnlinkedBy(userService.getCurrentUser());
				}
				default -> throw new IllegalArgumentException("Неизвестная массовая операция: " + operation);
			}
			incidentTaskLinkRepository.save(link);
		}
		saveSystemEntry(incident, "Выполнена массовая операция %s для %d заявок".formatted(operation, taskIds.size()));
		return changed(incident);
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> getSuggestions(Long incidentId) {
		SupportIncident incident = getAccessibleIncident(incidentId);
		Set<String> incidentTokens = tokens(String.join(" ", List.of(
				Objects.toString(incident.getTitle(), ""),
				Objects.toString(incident.getDescription(), ""),
				Objects.toString(incident.getImpactSummary(), ""),
				Objects.toString(incident.getServiceName(), ""),
				incident.getServices().stream().map(ru.ravel.ItDesk.model.Service::getName).filter(Objects::nonNull).collect(Collectors.joining(" "))
		)));
		Set<Long> linkedTaskIds = incidentTaskLinkRepository.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incidentId).stream()
				.map(IncidentTaskLink::getTask)
				.map(Task::getId)
				.collect(Collectors.toSet());
		Set<Long> incidentOrganizationIds = incident.getOrganizations().stream().map(Organization::getId).collect(Collectors.toSet());
		ZonedDateTime createdAfter = ZonedDateTime.now().minusDays(7);

		return taskRepository.findAll().stream()
				.filter(task -> task.getId() != null && !linkedTaskIds.contains(task.getId()))
				.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
				.filter(task -> task.getCreatedAt() == null || task.getCreatedAt().isAfter(createdAfter))
				.map(task -> suggestionForTask(task, incidentTokens, incidentOrganizationIds))
				.filter(Objects::nonNull)
				.sorted(Comparator.comparingDouble(item -> -((Number) item.get("score")).doubleValue()))
				.limit(30)
				.toList();
	}

	@Transactional(readOnly = true)
	public Map<String, Object> getIncidentByTask(Long taskId) {
		IncidentTaskLink link = incidentTaskLinkRepository.findFirstByTaskIdAndActiveTrue(taskId).orElse(null);
		if (link == null) {
			return null;
		}
		User currentUser = userService.getCurrentUser();
		SupportIncident incident = link.getIncident();
		if (!canUserView(incident, currentUser)) {
			return null;
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", incident.getId());
		result.put("incidentNumber", incident.getIncidentNumber());
		result.put("title", incident.getTitle());
		result.put("severity", incident.getSeverity());
		result.put("status", incident.getStatus());
		result.put("nextUpdateAt", incident.getNextUpdateAt());
		result.put("communicationOverdue", isCommunicationOverdue(incident));
		result.put("recoveryConfirmed", Boolean.TRUE.equals(link.getRecoveryConfirmed()));
		return result;
	}

	@Transactional(readOnly = true)
	public List<IncidentUpdateTemplate> getUpdateTemplates() {
		return incidentUpdateTemplateRepository.findAllByOrderByOrderNumberAscIdAsc();
	}

	@Transactional
	public void checkCommunicationDeadlines() {
		ZonedDateTime now = ZonedDateTime.now();
		for (SupportIncident incident : incidentRepository.findAllByStatusNotIn(List.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED))) {
			if (!isCommunicationOverdue(incident)) {
				continue;
			}
			if (incident.getCommunicationOverdueNotifiedAt() != null &&
					!incident.getCommunicationOverdueNotifiedAt().isBefore(incident.getNextUpdateAt())) {
				continue;
			}
			Set<User> recipients = new LinkedHashSet<>();
			addIfNotNull(recipients, incident.getLeader());
			addIfNotNull(recipients, incident.getCommunicationOwner());
			recipients.addAll(incident.getParticipants());
			for (User user : recipients) {
				if (user.getId() == null) {
					continue;
				}
				userNotificationService.send(new UserNotification(
						UserNotificationEvent.INCIDENT_UPDATE_OVERDUE,
						"Просрочено обновление по %s «%s»".formatted(incident.getIncidentNumber(), incident.getTitle()),
						user.getId()
				));
			}
			incident.setCommunicationOverdueNotifiedAt(now);
			incident.setUpdatedAt(now);
			incidentRepository.save(incident);
			saveSystemEntry(incident, "Обязательное обновление просрочено");
			publishIncidentChanged(incident.getId());
		}
	}

	// ------------------------------------------------------------------
	// Системные операции для автоматизаций и мониторинга.
	// Работают без пользовательской сессии и не бросают исключений на ожидаемых ситуациях
	// (заявка уже в другом инциденте и т.п.), чтобы не помечать транзакцию автоматизации rollback-only.
	// ------------------------------------------------------------------

	/**
	 * Поля черновика инцидента, заполненные детектором.
	 */
	public record SystemIncidentDraft(
			String title,
			String description,
			String publicDescription,
			String impactSummary,
			IncidentSeverity severity,
			Long serviceId,
			Long supportLineId,
			Long leaderId,
			Long technicalOwnerId,
			Collection<Long> participantIds,
			Collection<Long> clientIds,
			Collection<Long> taskIds,
			ZonedDateTime actualStartedAt,
			Integer updateIntervalMinutes
	) {
	}


	/**
	 * То же с указанием источника для таймлайна, например «мониторинг Zabbix».
	 */
	@Transactional
	public SupportIncident createDraftForSystem(SystemIncidentDraft draft, String source) {
		User system = SystemUser.getInstance();
		ZonedDateTime now = ZonedDateTime.now();
		SupportIncident incident = SupportIncident.builder()
				.title(draft.title())
				.description(Objects.requireNonNullElse(draft.description(), ""))
				.publicDescription(Objects.requireNonNullElse(draft.publicDescription(), ""))
				.impactSummary(Objects.requireNonNullElse(draft.impactSummary(), ""))
				.severity(draft.severity())
				.status(IncidentStatus.DRAFT)
				.createdBy(system)
				.createdAt(now)
				.updatedAt(now)
				.detectedAt(now)
				.actualStartedAt(draft.actualStartedAt())
				.updateIntervalMinutes(draft.updateIntervalMinutes() == null ? 30 : Math.max(5, draft.updateIntervalMinutes()))
				.build();
		if (draft.serviceId() != null) {
			incident.setServices(resolveServices(List.of(draft.serviceId())));
			incident.setServiceName(incident.getServices().stream()
					.map(ru.ravel.ItDesk.model.Service::getName)
					.filter(Objects::nonNull)
					.collect(Collectors.joining(", ")));
		}
		if (draft.supportLineId() != null) {
			supportLineRepository.findById(draft.supportLineId()).ifPresent(incident::setSupportLine);
		}
		if (draft.leaderId() != null) {
			userRepository.findById(draft.leaderId()).ifPresent(leader -> {
				incident.setLeader(leader);
				incident.setLeaderAssignedAt(now);
			});
		}
		if (draft.technicalOwnerId() != null) {
			userRepository.findById(draft.technicalOwnerId()).ifPresent(incident::setTechnicalOwner);
		}
		if (draft.participantIds() != null && !draft.participantIds().isEmpty()) {
			incident.getParticipants().addAll(userRepository.findAllById(draft.participantIds()));
		}
		if (draft.clientIds() != null) {
			for (Client client : clientRepository.findAllById(draft.clientIds())) {
				incident.getClients().add(client);
				if (client.getOrganization() != null) incident.getOrganizations().add(client.getOrganization());
			}
		}
		SupportIncident saved = incidentRepository.saveAndFlush(incident);
		saved.setIncidentNumber("INC-%06d".formatted(saved.getId()));
		saved = incidentRepository.saveAndFlush(saved);
		saveSystemEntry(saved, "Инцидент %s создан автоматически (%s)".formatted(saved.getIncidentNumber(), source));
		linkTasksForSystem(saved.getId(), draft.taskIds());
		publishIncidentChanged(saved.getId());
		return saved;
	}


	/**
	 * Привязывает заявки к инциденту от имени системы. Заявки, уже связанные с активным инцидентом,
	 * и несуществующие пропускаются.
	 *
	 * @return id реально привязанных заявок
	 */
	@Transactional
	public List<Long> linkTasksForSystem(Long incidentId, Collection<Long> taskIds) {
		if (incidentId == null || taskIds == null || taskIds.isEmpty()) return List.of();
		SupportIncident incident = incidentRepository.findById(incidentId).orElse(null);
		if (incident == null || !incident.getStatus().isActive()) return List.of();
		User system = SystemUser.getInstance();
		List<Long> linked = new ArrayList<>();
		for (Long taskId : taskIds.stream().filter(Objects::nonNull).distinct().toList()) {
			Task task = taskRepository.findById(taskId).orElse(null);
			Client client = task == null ? null : clientRepository.findByTaskId(taskId).orElse(null);
			if (task == null || client == null || incidentTaskLinkRepository.existsByTaskIdAndActiveTrue(taskId)) {
				continue;
			}
			incidentTaskLinkRepository.save(IncidentTaskLink.builder()
					.incident(incident)
					.task(task)
					.linkedBy(system)
					.primaryTask(false)
					.build());
			incident.getClients().add(client);
			if (client.getOrganization() != null) incident.getOrganizations().add(client.getOrganization());
			if (task.getService() != null) incident.getServices().add(task.getService());
			saveSystemEntry(incident, "Заявка #%d связана с инцидентом (автоматизация)".formatted(taskId));
			linked.add(taskId);
		}
		if (!linked.isEmpty()) {
			incident.setUpdatedAt(ZonedDateTime.now());
			incidentRepository.save(incident);
			publishIncidentChanged(incident.getId());
		}
		return linked;
	}


	/**
	 * Подтверждение восстановления по заявке от имени системы (например, клиент сам написал, что всё работает).
	 *
	 * @return true, если состояние изменилось
	 */
	@Transactional
	public boolean setRecoveryConfirmationForSystem(Long incidentId, Long taskId, boolean confirmed, String reason) {
		IncidentTaskLink link = incidentTaskLinkRepository.findFirstByIncidentIdAndTaskIdAndActiveTrue(incidentId, taskId)
				.orElse(null);
		if (link == null || Boolean.TRUE.equals(link.getRecoveryConfirmed()) == confirmed) {
			return false;
		}
		link.setRecoveryConfirmed(confirmed);
		link.setRecoveryConfirmedAt(confirmed ? ZonedDateTime.now() : null);
		link.setRecoveryConfirmedBy(confirmed ? SystemUser.getInstance() : null);
		incidentTaskLinkRepository.save(link);
		incidentTimelineEntryRepository.save(IncidentTimelineEntry.builder()
				.incident(link.getIncident())
				.type(IncidentTimelineType.RECOVERY_CONFIRMATION)
				.audience(IncidentTimelineAudience.INTERNAL)
				.text("По заявке #%d восстановление %s (%s)".formatted(taskId, confirmed ? "подтверждено" : "снято", reason))
				.createdAt(ZonedDateTime.now())
				.build());
		publishIncidentChanged(incidentId);
		return true;
	}


	/**
	 * Клиент сообщает, что после восстановления проблема сохраняется: запись в таймлайн и уведомление
	 * руководителю, техническому владельцу, ответственному за коммуникацию и участникам.
	 */
	@Transactional
	public void reportRecoveryProblemForSystem(Long incidentId, Long taskId, String details) {
		SupportIncident incident = incidentRepository.findById(incidentId).orElse(null);
		if (incident == null) {
			return;
		}
		saveSystemEntry(incident, "После восстановления проблема сохраняется: %s".formatted(details));
		Set<User> recipients = new LinkedHashSet<>();
		addIfNotNull(recipients, incident.getLeader());
		addIfNotNull(recipients, incident.getTechnicalOwner());
		addIfNotNull(recipients, incident.getCommunicationOwner());
		recipients.addAll(incident.getParticipants());
		for (User user : recipients) {
			if (user.getId() != null) {
				userNotificationService.send(new UserNotification(
						UserNotificationEvent.INCIDENT_RECOVERY_FAILED,
						"%s «%s»: клиент сообщает, что проблема сохраняется (заявка #%d)".formatted(
								incident.getIncidentNumber(), incident.getTitle(), taskId),
						user.getId()
				));
			}
		}
		publishIncidentChanged(incidentId);
	}


	/**
	 * Итог перевода инцидента к проверке восстановления.
	 */
	public enum RecoveryCheckResult {
		/** Переведён в «Наблюдение» */
		MOVED,
		/** Статус не изменён (черновик, уже наблюдение или восстановлен): оставлена заметка */
		NOTE_ONLY,
		/** Инцидент не найден или уже закрыт */
		SKIPPED
	}


	/**
	 * Проблема устранена (например, по данным мониторинга): объявленный инцидент переводится в «Наблюдение»,
	 * где проверяется восстановление у клиентов. Черновик и проверка не переводятся — их ещё не подтвердили
	 * и они не видны клиентам, поэтому только заметка.
	 */
	@Transactional
	public RecoveryCheckResult moveToRecoveryCheckForSystem(Long incidentId, String reason) {
		SupportIncident incident = incidentId == null ? null : incidentRepository.findById(incidentId).orElse(null);
		if (incident == null || !incident.getStatus().isActive()) {
			return RecoveryCheckResult.SKIPPED;
		}
		IncidentStatus status = incident.getStatus();
		if (status != IncidentStatus.DECLARED && status != IncidentStatus.DIAGNOSIS && status != IncidentStatus.MITIGATION) {
			saveSystemEntry(incident, status == IncidentStatus.DRAFT || status == IncidentStatus.VERIFYING
					? "%s. Инцидент ещё не объявлен: проверьте, нужен ли он, или отмените".formatted(reason)
					: reason);
			publishIncidentChanged(incident.getId());
			return RecoveryCheckResult.NOTE_ONLY;
		}
		ZonedDateTime now = ZonedDateTime.now();
		incident.setStatus(IncidentStatus.MONITORING);
		if (incident.getMitigatedAt() == null) incident.setMitigatedAt(now);
		if (incident.getNextUpdateAt() == null) {
			incident.setNextUpdateAt(now.plusMinutes(Objects.requireNonNullElse(incident.getUpdateIntervalMinutes(), 30)));
		}
		incident.setUpdatedAt(now);
		incidentRepository.save(incident);
		saveSystemEntry(incident, "Статус изменён: %s → %s. %s".formatted(status, IncidentStatus.MONITORING, reason));
		publishIncidentChanged(incident.getId());
		return RecoveryCheckResult.MOVED;
	}

	// ------------------------------------------------------------------
	// Системные операции для автоматизаций (детектор массовых инцидентов Jev).
	// Работают без пользовательской сессии и не бросают исключений на ожидаемых ситуациях
	// (заявка уже в другом инциденте и т.п.), чтобы не помечать транзакцию автоматизации rollback-only.
	// ------------------------------------------------------------------

	/**
	 * Поля черновика инцидента, заполненные детектором.
	 */
	public record SystemIncidentDraft(
			String title,
			String description,
			String publicDescription,
			String impactSummary,
			IncidentSeverity severity,
			Long serviceId,
			Long supportLineId,
			Long leaderId,
			Long technicalOwnerId,
			Collection<Long> participantIds,
			Collection<Long> clientIds,
			Collection<Long> taskIds,
			ZonedDateTime actualStartedAt,
			Integer updateIntervalMinutes
	) {
	}


	/**
	 * Создаёт инцидент в статусе DRAFT от имени системы и привязывает свободные заявки.
	 */
	@Transactional
	public SupportIncident createDraftForSystem(SystemIncidentDraft draft) {
		return createDraftForSystem(draft, "детектор Jev");
	}


	/**
	 * То же с указанием источника для таймлайна, например «мониторинг Zabbix».
	 */
	@Transactional
	public SupportIncident createDraftForSystem(SystemIncidentDraft draft, String source) {
		User system = SystemUser.getInstance();
		ZonedDateTime now = ZonedDateTime.now();
		SupportIncident incident = SupportIncident.builder()
				.title(draft.title())
				.description(Objects.requireNonNullElse(draft.description(), ""))
				.publicDescription(Objects.requireNonNullElse(draft.publicDescription(), ""))
				.impactSummary(Objects.requireNonNullElse(draft.impactSummary(), ""))
				.severity(draft.severity())
				.status(IncidentStatus.DRAFT)
				.createdBy(system)
				.createdAt(now)
				.updatedAt(now)
				.detectedAt(now)
				.actualStartedAt(draft.actualStartedAt())
				.updateIntervalMinutes(draft.updateIntervalMinutes() == null ? 30 : Math.max(5, draft.updateIntervalMinutes()))
				.build();
		if (draft.serviceId() != null) {
			incident.setServices(resolveServices(List.of(draft.serviceId())));
			incident.setServiceName(incident.getServices().stream()
					.map(ru.ravel.ItDesk.model.Service::getName)
					.filter(Objects::nonNull)
					.collect(Collectors.joining(", ")));
		}
		if (draft.supportLineId() != null) {
			supportLineRepository.findById(draft.supportLineId()).ifPresent(incident::setSupportLine);
		}
		if (draft.leaderId() != null) {
			userRepository.findById(draft.leaderId()).ifPresent(leader -> {
				incident.setLeader(leader);
				incident.setLeaderAssignedAt(now);
			});
		}
		if (draft.technicalOwnerId() != null) {
			userRepository.findById(draft.technicalOwnerId()).ifPresent(incident::setTechnicalOwner);
		}
		if (draft.participantIds() != null && !draft.participantIds().isEmpty()) {
			incident.getParticipants().addAll(userRepository.findAllById(draft.participantIds()));
		}
		if (draft.clientIds() != null) {
			for (Client client : clientRepository.findAllById(draft.clientIds())) {
				incident.getClients().add(client);
				if (client.getOrganization() != null) incident.getOrganizations().add(client.getOrganization());
			}
		}
		SupportIncident saved = incidentRepository.saveAndFlush(incident);
		saved.setIncidentNumber("INC-%06d".formatted(saved.getId()));
		saved = incidentRepository.saveAndFlush(saved);
		saveSystemEntry(saved, "Инцидент %s создан автоматически (%s)".formatted(saved.getIncidentNumber(), source));
		linkTasksForSystem(saved.getId(), draft.taskIds());
		publishIncidentChanged(saved.getId());
		return saved;
	}


	/**
	 * Привязывает заявки к инциденту от имени системы. Заявки, уже связанные с активным инцидентом,
	 * и несуществующие пропускаются.
	 *
	 * @return id реально привязанных заявок
	 */
	@Transactional
	public List<Long> linkTasksForSystem(Long incidentId, Collection<Long> taskIds) {
		if (incidentId == null || taskIds == null || taskIds.isEmpty()) return List.of();
		SupportIncident incident = incidentRepository.findById(incidentId).orElse(null);
		if (incident == null || !incident.getStatus().isActive()) return List.of();
		User system = SystemUser.getInstance();
		List<Long> linked = new ArrayList<>();
		for (Long taskId : taskIds.stream().filter(Objects::nonNull).distinct().toList()) {
			Task task = taskRepository.findById(taskId).orElse(null);
			Client client = task == null ? null : clientRepository.findByTaskId(taskId).orElse(null);
			if (task == null || client == null || incidentTaskLinkRepository.existsByTaskIdAndActiveTrue(taskId)) {
				continue;
			}
			incidentTaskLinkRepository.save(IncidentTaskLink.builder()
					.incident(incident)
					.task(task)
					.linkedBy(system)
					.primaryTask(false)
					.build());
			incident.getClients().add(client);
			if (client.getOrganization() != null) incident.getOrganizations().add(client.getOrganization());
			if (task.getService() != null) incident.getServices().add(task.getService());
			saveSystemEntry(incident, "Заявка #%d связана с инцидентом (детектор Jev)".formatted(taskId));
			linked.add(taskId);
		}
		if (!linked.isEmpty()) {
			incident.setUpdatedAt(ZonedDateTime.now());
			incidentRepository.save(incident);
			publishIncidentChanged(incident.getId());
		}
		return linked;
	}


	/**
	 * Подтверждение восстановления по заявке от имени системы (например, клиент сам написал, что всё работает).
	 *
	 * @return true, если состояние изменилось
	 */
	@Transactional
	public boolean setRecoveryConfirmationForSystem(Long incidentId, Long taskId, boolean confirmed, String reason) {
		IncidentTaskLink link = incidentTaskLinkRepository.findFirstByIncidentIdAndTaskIdAndActiveTrue(incidentId, taskId)
				.orElse(null);
		if (link == null || Boolean.TRUE.equals(link.getRecoveryConfirmed()) == confirmed) {
			return false;
		}
		link.setRecoveryConfirmed(confirmed);
		link.setRecoveryConfirmedAt(confirmed ? ZonedDateTime.now() : null);
		link.setRecoveryConfirmedBy(confirmed ? SystemUser.getInstance() : null);
		incidentTaskLinkRepository.save(link);
		incidentTimelineEntryRepository.save(IncidentTimelineEntry.builder()
				.incident(link.getIncident())
				.type(IncidentTimelineType.RECOVERY_CONFIRMATION)
				.audience(IncidentTimelineAudience.INTERNAL)
				.text("По заявке #%d восстановление %s (%s)".formatted(taskId, confirmed ? "подтверждено" : "снято", reason))
				.createdAt(ZonedDateTime.now())
				.build());
		publishIncidentChanged(incidentId);
		return true;
	}


	/**
	 * Клиент сообщает, что после восстановления проблема сохраняется: запись в таймлайн и уведомление
	 * руководителю, техническому владельцу, ответственному за коммуникацию и участникам.
	 */
	@Transactional
	public void reportRecoveryProblemForSystem(Long incidentId, Long taskId, String details) {
		SupportIncident incident = incidentRepository.findById(incidentId).orElse(null);
		if (incident == null) {
			return;
		}
		saveSystemEntry(incident, "После восстановления проблема сохраняется: %s".formatted(details));
		Set<User> recipients = new LinkedHashSet<>();
		addIfNotNull(recipients, incident.getLeader());
		addIfNotNull(recipients, incident.getTechnicalOwner());
		addIfNotNull(recipients, incident.getCommunicationOwner());
		recipients.addAll(incident.getParticipants());
		for (User user : recipients) {
			if (user.getId() != null) {
				userNotificationService.send(new UserNotification(
						UserNotificationEvent.INCIDENT_RECOVERY_FAILED,
						"%s «%s»: клиент сообщает, что проблема сохраняется (заявка #%d)".formatted(
								incident.getIncidentNumber(), incident.getTitle(), taskId),
						user.getId()
				));
			}
		}
		publishIncidentChanged(incidentId);
	}


	/**
	 * Итог перевода инцидента к проверке восстановления.
	 */
	public enum RecoveryCheckResult {
		/** Переведён в «Наблюдение» */
		MOVED,
		/** Статус не изменён (черновик, уже наблюдение или восстановлен): оставлена заметка */
		NOTE_ONLY,
		/** Инцидент не найден или уже закрыт */
		SKIPPED
	}


	/**
	 * Проблема устранена (например, по данным мониторинга): объявленный инцидент переводится в «Наблюдение»,
	 * где проверяется восстановление у клиентов. Черновик и проверка не переводятся — их ещё не подтвердили
	 * и они не видны клиентам, поэтому только заметка.
	 */
	@Transactional
	public RecoveryCheckResult moveToRecoveryCheckForSystem(Long incidentId, String reason) {
		SupportIncident incident = incidentId == null ? null : incidentRepository.findById(incidentId).orElse(null);
		if (incident == null || !incident.getStatus().isActive()) {
			return RecoveryCheckResult.SKIPPED;
		}
		IncidentStatus status = incident.getStatus();
		if (status != IncidentStatus.DECLARED && status != IncidentStatus.DIAGNOSIS && status != IncidentStatus.MITIGATION) {
			saveSystemEntry(incident, status == IncidentStatus.DRAFT || status == IncidentStatus.VERIFYING
					? "%s. Инцидент ещё не объявлен: проверьте, нужен ли он, или отмените".formatted(reason)
					: reason);
			publishIncidentChanged(incident.getId());
			return RecoveryCheckResult.NOTE_ONLY;
		}
		ZonedDateTime now = ZonedDateTime.now();
		incident.setStatus(IncidentStatus.MONITORING);
		if (incident.getMitigatedAt() == null) incident.setMitigatedAt(now);
		if (incident.getNextUpdateAt() == null) {
			incident.setNextUpdateAt(now.plusMinutes(Objects.requireNonNullElse(incident.getUpdateIntervalMinutes(), 30)));
		}
		incident.setUpdatedAt(now);
		incidentRepository.save(incident);
		saveSystemEntry(incident, "Статус изменён: %s → %s. %s".formatted(status, IncidentStatus.MONITORING, reason));
		publishIncidentChanged(incident.getId());
		return RecoveryCheckResult.MOVED;
	}


	/**
	 * Системная запись во внутренний таймлайн инцидента.
	 */
	@Transactional
	public void addSystemNote(Long incidentId, String text) {
		incidentRepository.findById(incidentId).ifPresent(incident -> {
			saveSystemEntry(incident, text);
			publishIncidentChanged(incident.getId());
		});
	}


	/**
	 * Realtime-событие «инцидент изменился» — после коммита, только пользователям, которые видят инцидент.
	 */
	public void publishIncidentChanged(Long incidentId) {
		if (incidentId == null) {
			return;
		}
		afterCommitExecutor.runOnce("incident:" + incidentId, () -> incidentRepository.findById(incidentId)
				.ifPresent(incident -> {
					for (User user : userService.getUsers()) {
						if (user.isEnabled() && canUserView(incident, user)) {
							webSocketService.incidentUpdated(user.getUsername(), incidentId);
						}
					}
				}));
	}


	private Map<String, Object> changed(SupportIncident incident) {
		publishIncidentChanged(incident.getId());
		return toDetailDto(incident);
	}



	/**
	 * Системная запись во внутренний таймлайн инцидента.
	 */
	@Transactional
	public void addSystemNote(Long incidentId, String text) {
		incidentRepository.findById(incidentId).ifPresent(incident -> {
			saveSystemEntry(incident, text);
			publishIncidentChanged(incident.getId());
		});
	}


	/**
	 * Realtime-событие «инцидент изменился» — после коммита, только пользователям, которые видят инцидент.
	 */
	public void publishIncidentChanged(Long incidentId) {
		if (incidentId == null) {
			return;
		}
		afterCommitExecutor.runOnce("incident:" + incidentId, () -> incidentRepository.findById(incidentId)
				.ifPresent(incident -> {
					for (User user : userService.getUsers()) {
						if (user.isEnabled() && canUserView(incident, user)) {
							webSocketService.incidentUpdated(user.getUsername(), incidentId);
						}
					}
				}));
	}


	private Map<String, Object> changed(SupportIncident incident) {
		publishIncidentChanged(incident.getId());
		return toDetailDto(incident);
	}


	private void applyRequest(SupportIncident incident, IncidentSaveRequest request) {
		if (request == null) {
			return;
		}
		if (request.getTitle() != null) incident.setTitle(request.getTitle().trim());
		if (request.getDescription() != null) incident.setDescription(request.getDescription());
		if (request.getPublicDescription() != null) incident.setPublicDescription(request.getPublicDescription());
		if (request.getImpactSummary() != null) incident.setImpactSummary(request.getImpactSummary());
		if (request.getServiceIds() != null) {
			incident.setServices(resolveServices(request.getServiceIds()));
			incident.setServiceName(incident.getServices().stream()
					.map(ru.ravel.ItDesk.model.Service::getName)
					.filter(Objects::nonNull)
					.collect(Collectors.joining(", ")));
		} else if (request.getServiceName() != null) {
			incident.setServiceName(blankToNull(request.getServiceName()));
		}
		if (request.getWorkaround() != null) incident.setWorkaround(request.getWorkaround());
		if (request.getRootCause() != null) incident.setRootCause(request.getRootCause());
		if (request.getResolution() != null) incident.setResolution(request.getResolution());
		if (request.getPostmortemSummary() != null) incident.setPostmortemSummary(request.getPostmortemSummary());
		if (request.getSeverity() != null) incident.setSeverity(parseEnum(request.getSeverity(), IncidentSeverity.class, incident.getSeverity()));
		if (request.getStatus() != null) incident.setStatus(parseEnum(request.getStatus(), IncidentStatus.class, incident.getStatus()));
		if (request.getUpdateIntervalMinutes() != null) incident.setUpdateIntervalMinutes(Math.max(5, request.getUpdateIntervalMinutes()));

		if (request.getLeaderId() != null) {
			User leader = getUser(request.getLeaderId());
			if (!Objects.equals(idOf(incident.getLeader()), leader.getId())) {
				incident.setLeaderAssignedAt(ZonedDateTime.now());
			}
			incident.setLeader(leader);
		}
		if (request.getTechnicalOwnerId() != null) incident.setTechnicalOwner(getUser(request.getTechnicalOwnerId()));
		if (request.getCommunicationOwnerId() != null) incident.setCommunicationOwner(getUser(request.getCommunicationOwnerId()));
		if (request.getSupportLineId() != null) incident.setSupportLine(supportLineRepository.findById(request.getSupportLineId()).orElseThrow());
		if (request.getParticipantIds() != null) incident.setParticipants(resolveUsers(request.getParticipantIds()));
		if (request.getObserverIds() != null) incident.setObservers(resolveUsers(request.getObserverIds()));
		if (request.getOrganizationIds() != null) incident.setOrganizations(resolveOrganizations(request.getOrganizationIds()));
		if (request.getClientIds() != null) incident.setClients(resolveClients(request.getClientIds()));

		if (request.getActualStartedAt() != null) incident.setActualStartedAt(request.getActualStartedAt());
		if (request.getDetectedAt() != null) incident.setDetectedAt(request.getDetectedAt());
		if (request.getDeclaredAt() != null) incident.setDeclaredAt(request.getDeclaredAt());
		if (request.getMitigatedAt() != null) incident.setMitigatedAt(request.getMitigatedAt());
		if (request.getRestoredAt() != null) incident.setRestoredAt(request.getRestoredAt());
		if (request.getClosedAt() != null) incident.setClosedAt(request.getClosedAt());
		if (request.getNextUpdateAt() != null) {
			incident.setNextUpdateAt(request.getNextUpdateAt());
			incident.setCommunicationOverdueNotifiedAt(null);
		}

		ZonedDateTime now = ZonedDateTime.now();
		if (incident.getStatus() == IncidentStatus.DECLARED && incident.getDeclaredAt() == null) incident.setDeclaredAt(now);
		if (incident.getStatus() == IncidentStatus.MITIGATION && incident.getMitigatedAt() == null) incident.setMitigatedAt(now);
		if (incident.getStatus() == IncidentStatus.RESTORED && incident.getRestoredAt() == null) incident.setRestoredAt(now);
		if (incident.getStatus() == IncidentStatus.CLOSED && incident.getClosedAt() == null) incident.setClosedAt(now);
		if (incident.getStatus().requiresCommunicationControl() && incident.getNextUpdateAt() == null) {
			incident.setNextUpdateAt(now.plusMinutes(Objects.requireNonNullElse(incident.getUpdateIntervalMinutes(), 30)));
		}
	}

	private void validateIncident(SupportIncident incident) {
		if (incident.getTitle() == null || incident.getTitle().isBlank()) {
			throw new IllegalArgumentException("Название инцидента обязательно");
		}
		boolean criticalActive = (incident.getSeverity() == IncidentSeverity.P1 || incident.getSeverity() == IncidentSeverity.P2)
				&& incident.getStatus().requiresCommunicationControl();
		if (criticalActive && incident.getLeader() == null) {
			throw new IllegalArgumentException("Для активного P1/P2 необходимо назначить руководителя инцидента");
		}
		if (criticalActive && (incident.getImpactSummary() == null || incident.getImpactSummary().isBlank())) {
			throw new IllegalArgumentException("Для активного P1/P2 необходимо описать влияние");
		}
	}

	private void linkTasksInternal(SupportIncident incident, Collection<Long> taskIds, Long primaryTaskId, User currentUser) {
		if (taskIds == null) return;
		for (Long taskId : taskIds.stream().filter(Objects::nonNull).distinct().toList()) {
			Task task = taskRepository.findById(taskId).orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + taskId));
			Client client = clientRepository.findByTaskId(taskId).orElseThrow(() -> new IllegalArgumentException("Не найден клиент заявки: " + taskId));
			userService.assertCurrentUserCanAccessTask(client, task);
			IncidentTaskLink activeLink = incidentTaskLinkRepository.findFirstByTaskIdAndActiveTrue(taskId).orElse(null);
			if (activeLink != null) {
				if (Objects.equals(activeLink.getIncident().getId(), incident.getId())) {
					continue;
				}
				throw new IllegalStateException("Заявка #%d уже связана с активным инцидентом %s".formatted(taskId, activeLink.getIncident().getIncidentNumber()));
			}
			if (Objects.equals(primaryTaskId, taskId)) {
				incidentTaskLinkRepository.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incident.getId())
						.forEach(link -> {
							link.setPrimaryTask(false);
							incidentTaskLinkRepository.save(link);
						});
			}
			incidentTaskLinkRepository.save(IncidentTaskLink.builder()
					.incident(incident)
					.task(task)
					.linkedBy(currentUser)
					.primaryTask(Objects.equals(primaryTaskId, taskId))
					.build());
			incident.getClients().add(client);
			if (client.getOrganization() != null) incident.getOrganizations().add(client.getOrganization());
			if (task.getService() != null) incident.getServices().add(task.getService());
			saveSystemEntry(incident, "Заявка #%d связана с инцидентом".formatted(taskId));
		}
		incident.setUpdatedAt(ZonedDateTime.now());
		incidentRepository.save(incident);
	}

	private Map<String, Object> suggestionForTask(Task task, Set<String> incidentTokens, Set<Long> incidentOrganizationIds) {
		Client client = clientRepository.findByTaskId(task.getId()).orElse(null);
		if (client == null || !userService.canCurrentUserAccessTask(client, task) || incidentTaskLinkRepository.existsByTaskIdAndActiveTrue(task.getId())) {
			return null;
		}
		Set<String> taskTokens = tokens(Objects.toString(task.getName(), "") + " " + Objects.toString(task.getDescription(), ""));
		if (taskTokens.isEmpty()) return null;
		Set<String> intersection = new HashSet<>(incidentTokens);
		intersection.retainAll(taskTokens);
		Set<String> union = new HashSet<>(incidentTokens);
		union.addAll(taskTokens);
		double score = union.isEmpty() ? 0 : (double) intersection.size() / union.size();
		if (client.getOrganization() != null && incidentOrganizationIds.contains(client.getOrganization().getId())) score += 0.25;
		if (task.getService() != null && incidentTokens.containsAll(tokens(Objects.toString(task.getService().getName(), "")))) score += 0.25;
		if (task.getPriority() != null && Boolean.TRUE.equals(task.getPriority().getCritical())) score += 0.1;
		if (score < 0.12) return null;
		Map<String, Object> result = taskDto(task, client, null);
		result.put("score", Math.min(1, score));
		result.put("matchedTerms", intersection.stream().sorted().limit(8).toList());
		return result;
	}

	private void publishUpdateToClients(SupportIncident incident, String text) {
		if (incident == null || text == null || text.isBlank()) {
			return;
		}
		List<Long> failedClientIds = new ArrayList<>();
		String messageText = "[%s] %s".formatted(incident.getIncidentNumber(), text);
		for (Client client : incident.getClients()) {
			if (client == null || client.getId() == null) {
				continue;
			}
			try {
				clientService.sendMessageForSystem(client.getId(), Message.builder()
						.text(messageText)
						.isComment(false)
						.build());
			} catch (Exception ignored) {
				failedClientIds.add(client.getId());
			}
		}
		if (!failedClientIds.isEmpty()) {
			saveSystemEntry(incident, "Не удалось доставить клиентское обновление клиентам: " + failedClientIds);
		}
	}

	private void registerPublishedUpdate(SupportIncident incident, ZonedDateTime now, ZonedDateTime requestedNextUpdateAt) {
		if (incident.getNextUpdateAt() != null) {
			incident.setPlannedUpdatesCount(Objects.requireNonNullElse(incident.getPlannedUpdatesCount(), 0) + 1);
			if (!now.isAfter(incident.getNextUpdateAt())) {
				incident.setOnTimeUpdatesCount(Objects.requireNonNullElse(incident.getOnTimeUpdatesCount(), 0) + 1);
			}
		}
		incident.setLastPublishedUpdateAt(now);
		incident.setNextUpdateAt(requestedNextUpdateAt != null
				? requestedNextUpdateAt
				: now.plusMinutes(Objects.requireNonNullElse(incident.getUpdateIntervalMinutes(), 30)));
		incident.setCommunicationOverdueNotifiedAt(null);
	}

	private Map<Long, IncidentTaskLinkRepository.IncidentTaskStats> loadTaskStats(Collection<SupportIncident> incidents) {
		List<Long> incidentIds = incidents == null ? List.of() : incidents.stream()
				.filter(Objects::nonNull)
				.map(SupportIncident::getId)
				.filter(Objects::nonNull)
				.toList();
		if (incidentIds.isEmpty()) return Map.of();
		return incidentTaskLinkRepository.summarizeActiveLinks(incidentIds).stream()
				.collect(Collectors.toMap(IncidentTaskLinkRepository.IncidentTaskStats::getIncidentId, Function.identity()));
	}

	private Map<String, Object> toSummaryDto(SupportIncident incident) {
		List<IncidentTaskLink> links = incidentTaskLinkRepository.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incident.getId());
		return toSummaryDto(incident, links.size(), links.stream()
				.filter(link -> Boolean.TRUE.equals(link.getRecoveryConfirmed()))
				.count());
	}

	private Map<String, Object> toSummaryDto(
			SupportIncident incident,
			IncidentTaskLinkRepository.IncidentTaskStats stats
	) {
		long linkedTasksCount = stats == null || stats.getLinkedTasksCount() == null ? 0 : stats.getLinkedTasksCount();
		long confirmedTasksCount = stats == null || stats.getConfirmedTasksCount() == null ? 0 : stats.getConfirmedTasksCount();
		return toSummaryDto(incident, linkedTasksCount, confirmedTasksCount);
	}

	private Map<String, Object> toSummaryDto(
			SupportIncident incident,
			long linkedTasksCount,
			long confirmedTasksCount
	) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", incident.getId());
		result.put("incidentNumber", incident.getIncidentNumber());
		result.put("title", incident.getTitle());
		result.put("description", incident.getDescription());
		result.put("publicDescription", incident.getPublicDescription());
		result.put("impactSummary", incident.getImpactSummary());
		result.put("serviceName", incident.getServices().isEmpty()
				? incident.getServiceName()
				: incident.getServices().stream().map(ru.ravel.ItDesk.model.Service::getName).filter(Objects::nonNull).collect(Collectors.joining(", ")));
		result.put("services", incident.getServices().stream().sorted().map(this::serviceDto).toList());
		result.put("severity", incident.getSeverity());
		result.put("status", incident.getStatus());
		result.put("leader", userDto(incident.getLeader()));
		result.put("technicalOwner", userDto(incident.getTechnicalOwner()));
		result.put("communicationOwner", userDto(incident.getCommunicationOwner()));
		result.put("supportLine", supportLineDto(incident.getSupportLine()));
		result.put("organizations", incident.getOrganizations().stream().sorted().map(this::organizationDto).toList());
		result.put("clientsCount", incident.getClients().size());
		result.put("organizationsCount", incident.getOrganizations().size());
		result.put("linkedTasksCount", linkedTasksCount);
		result.put("confirmedTasksCount", confirmedTasksCount);
		result.put("actualStartedAt", incident.getActualStartedAt());
		result.put("detectedAt", incident.getDetectedAt());
		result.put("declaredAt", incident.getDeclaredAt());
		result.put("restoredAt", incident.getRestoredAt());
		result.put("closedAt", incident.getClosedAt());
		result.put("nextUpdateAt", incident.getNextUpdateAt());
		result.put("lastPublishedUpdateAt", incident.getLastPublishedUpdateAt());
		result.put("updateIntervalMinutes", incident.getUpdateIntervalMinutes());
		result.put("createdAt", incident.getCreatedAt());
		result.put("updatedAt", incident.getUpdatedAt());
		result.put("communicationOverdue", isCommunicationOverdue(incident));
		result.put("communicationOverdueSeconds", communicationOverdueSeconds(incident));
		result.put("metrics", metricsDto(incident));
		return result;
	}

	private Map<String, Object> toDetailDto(SupportIncident incident) {
		List<IncidentTaskLink> links = incidentTaskLinkRepository.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incident.getId());
		long confirmedTasksCount = links.stream()
				.filter(link -> Boolean.TRUE.equals(link.getRecoveryConfirmed()))
				.count();
		Map<String, Object> result = new LinkedHashMap<>(toSummaryDto(incident, links.size(), confirmedTasksCount));
		result.put("workaround", incident.getWorkaround());
		result.put("rootCause", incident.getRootCause());
		result.put("resolution", incident.getResolution());
		result.put("postmortemSummary", incident.getPostmortemSummary());
		result.put("createdBy", userDto(incident.getCreatedBy()));
		result.put("participants", incident.getParticipants().stream().map(this::userDto).toList());
		result.put("observers", incident.getObservers().stream().map(this::userDto).toList());
		result.put("clients", incident.getClients().stream().map(this::clientDto).toList());
		result.put("taskLinks", links.stream().map(this::taskLinkDto).toList());
		User currentUser = userService.getCurrentUser();
		result.put("timeline", incidentTimelineEntryRepository.findAllByIncidentIdOrderByCreatedAtAscIdAsc(incident.getId()).stream()
				.filter(entry -> canUserSeeAudience(entry.getAudience(), currentUser))
				.map(this::timelineDto)
				.toList());
		return result;
	}

	private Map<String, Object> taskLinkDto(IncidentTaskLink link) {
		Client client = clientRepository.findByTaskId(link.getTask().getId()).orElse(null);
		Map<String, Object> result = taskDto(link.getTask(), client, link);
		result.put("linkId", link.getId());
		result.put("linkedAt", link.getLinkedAt());
		result.put("linkedBy", userDto(link.getLinkedBy()));
		result.put("primaryTask", Boolean.TRUE.equals(link.getPrimaryTask()));
		result.put("recoveryConfirmed", Boolean.TRUE.equals(link.getRecoveryConfirmed()));
		result.put("recoveryConfirmedAt", link.getRecoveryConfirmedAt());
		result.put("recoveryConfirmedBy", userDto(link.getRecoveryConfirmedBy()));
		return result;
	}

	private Map<String, Object> taskDto(Task task, Client client, IncidentTaskLink link) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", task.getId());
		result.put("name", task.getName());
		result.put("description", task.getDescription());
		result.put("status", task.getStatus() == null ? null : task.getStatus().getName());
		result.put("priority", task.getPriority() == null ? null : task.getPriority().getName());
		result.put("critical", task.getPriority() != null && Boolean.TRUE.equals(task.getPriority().getCritical()));
		result.put("executor", userDto(task.getExecutor()));
		result.put("supportLine", supportLineDto(task.getSupportLine()));
		result.put("service", serviceDto(task.getService()));
		result.put("completed", Boolean.TRUE.equals(task.getCompleted()));
		result.put("createdAt", task.getCreatedAt());
		result.put("lastActivity", task.getLastActivity());
		result.put("client", clientDto(client));
		result.put("organization", client == null ? null : organizationDto(client.getOrganization()));
		return result;
	}

	private Map<String, Object> timelineDto(IncidentTimelineEntry entry) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", entry.getId());
		result.put("type", entry.getType());
		result.put("audience", entry.getAudience());
		result.put("text", entry.getText());
		result.put("author", userDto(entry.getAuthor()));
		result.put("createdAt", entry.getCreatedAt());
		result.put("templateKey", entry.getTemplateKey());
		return result;
	}

	private Map<String, Object> metricsDto(SupportIncident incident) {
		Map<String, Object> result = new LinkedHashMap<>();
		ZonedDateTime start = firstNonNull(incident.getActualStartedAt(), incident.getDetectedAt(), incident.getCreatedAt());
		ZonedDateTime end = firstNonNull(incident.getRestoredAt(), incident.getClosedAt(), ZonedDateTime.now());
		result.put("durationSeconds", secondsBetween(start, end));
		result.put("mttdSeconds", secondsBetween(incident.getActualStartedAt(), incident.getDetectedAt()));
		result.put("mttaSeconds", secondsBetween(incident.getDetectedAt(), incident.getLeaderAssignedAt()));
		result.put("timeToDeclareSeconds", secondsBetween(incident.getDetectedAt(), incident.getDeclaredAt()));
		result.put("timeToMitigateSeconds", secondsBetween(start, incident.getMitigatedAt()));
		result.put("mttrSeconds", secondsBetween(start, incident.getRestoredAt()));
		int planned = Objects.requireNonNullElse(incident.getPlannedUpdatesCount(), 0);
		int onTime = Objects.requireNonNullElse(incident.getOnTimeUpdatesCount(), 0);
		result.put("plannedUpdatesCount", planned);
		result.put("onTimeUpdatesCount", onTime);
		result.put("communicationCompliancePercent", planned == 0 ? 100 : Math.round((double) onTime * 100 / planned));
		return result;
	}

	private Map<String, Object> toPortalDto(SupportIncident incident, Client client) {
		List<IncidentTaskLink> clientLinks = incidentTaskLinkRepository
				.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(incident.getId()).stream()
				.filter(link -> clientRepository.findByTaskId(link.getTask().getId())
						.map(linkedClient -> Objects.equals(linkedClient.getId(), client.getId()))
						.orElse(false))
				.toList();
		List<Map<String, Object>> updates = incidentTimelineEntryRepository
				.findAllByIncidentIdOrderByCreatedAtAscIdAsc(incident.getId()).stream()
				.filter(entry -> entry.getAudience() == IncidentTimelineAudience.CLIENTS || entry.getAudience() == IncidentTimelineAudience.PUBLIC)
				.map(this::portalTimelineDto)
				.toList();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", incident.getId());
		result.put("incidentNumber", incident.getIncidentNumber());
		result.put("title", incident.getTitle());
		result.put("severity", incident.getSeverity());
		result.put("status", incident.getStatus());
		result.put("publicDescription", incident.getPublicDescription());
		result.put("impactSummary", incident.getImpactSummary());
		result.put("serviceName", incident.getServices().isEmpty()
				? incident.getServiceName()
				: incident.getServices().stream().map(ru.ravel.ItDesk.model.Service::getName).filter(Objects::nonNull).collect(Collectors.joining(", ")));
		result.put("services", incident.getServices().stream().sorted().map(this::serviceDto).toList());
		result.put("workaround", incident.getWorkaround());
		result.put("nextUpdateAt", incident.getNextUpdateAt());
		result.put("restoredAt", incident.getRestoredAt());
		result.put("lastUpdate", updates.isEmpty() ? null : updates.getLast());
		result.put("updates", updates);
		result.put("recoveryConfirmationAvailable", !clientLinks.isEmpty());
		result.put("recoveryConfirmed", !clientLinks.isEmpty() && clientLinks.stream().allMatch(link -> Boolean.TRUE.equals(link.getRecoveryConfirmed())));
		return result;
	}

	private Map<String, Object> portalTimelineDto(IncidentTimelineEntry entry) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", entry.getId());
		result.put("type", entry.getType());
		result.put("text", entry.getText());
		result.put("createdAt", entry.getCreatedAt());
		return result;
	}

	private boolean isVisibleInPortal(SupportIncident incident) {
		return incident != null && incident.getStatus() != null && switch (incident.getStatus()) {
			case DECLARED, DIAGNOSIS, MITIGATION, MONITORING, RESTORED -> true;
			default -> false;
		};
	}

	private Client getCurrentPortalClient() {
		User user = userService.getCurrentUser();
		Long clientId = resolveClientIdFromUser(user);
		if (clientId == null) {
			throw new AccessDeniedException("Учётная запись не привязана к клиенту");
		}
		Client client = clientRepository.findById(clientId)
				.orElseThrow(() -> new AccessDeniedException("Карточка клиента не найдена"));
		userService.assertUserCanAccessClient(user, client);
		return client;
	}

	private Long resolveClientIdFromUser(User user) {
		if (user == null) return null;
		try {
			Object value = user.getClass().getMethod("getClientId").invoke(user);
			return value instanceof Number number ? number.longValue() : null;
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private boolean affectsClient(SupportIncident incident, Client client) {
		if (incident == null || client == null) return false;
		if (incident.getClients().stream().anyMatch(item -> Objects.equals(item.getId(), client.getId()))) return true;
		Long organizationId = client.getOrganization() == null ? null : client.getOrganization().getId();
		return organizationId != null && incident.getOrganizations().stream()
				.anyMatch(item -> Objects.equals(item.getId(), organizationId));
	}

	private String clientDisplayName(Client client) {
		String value = (Objects.toString(client.getLastname(), "") + " " + Objects.toString(client.getFirstname(), "")).trim();
		return value.isBlank() ? "#" + client.getId() : value;
	}

	private SupportIncident getAccessibleIncident(Long incidentId) {
		SupportIncident incident = incidentRepository.findById(incidentId)
				.orElseThrow(() -> new IllegalArgumentException("Инцидент не найден"));
		User currentUser = userService.getCurrentUser();
		if (!canUserView(incident, currentUser)) throw new AccessDeniedException("Нет доступа к инциденту");
		return incident;
	}

	private SupportIncident getAccessibleIncidentForChange(Long incidentId) {
		SupportIncident incident = getAccessibleIncident(incidentId);
		User current = userService.getCurrentUser();
		if (!userService.isAdmin(current) && !userService.isOperator(current)) throw new AccessDeniedException("Недостаточно прав");
		return incident;
	}

	private boolean canUserView(SupportIncident incident, User user) {
		if (incident == null || user == null) return false;
		// Менеджер, как и в заявках, видит инциденты всех организаций
		if (userService.isAdmin(user) || userService.isManager(user)) return true;
		boolean operator = userService.isOperator(user);
		boolean observer = hasAuthority(user, "OBSERVER");
		if (!operator && !observer) return false;
		if (Objects.equals(idOf(incident.getLeader()), user.getId()) ||
				Objects.equals(idOf(incident.getTechnicalOwner()), user.getId()) ||
				Objects.equals(idOf(incident.getCommunicationOwner()), user.getId())) return true;
		if (incident.getParticipants().stream().anyMatch(item -> Objects.equals(item.getId(), user.getId())) ||
				incident.getObservers().stream().anyMatch(item -> Objects.equals(item.getId(), user.getId()))) return true;

		Set<Organization> incidentOrganizations = incident.getOrganizations();
		if (operator && incidentOrganizations.isEmpty()) return true;
		List<Organization> availableOrganizations = Optional.ofNullable(user.getAvailableOrganizations())
				.orElseGet(Collections::emptyList);
		if (operator && availableOrganizations.isEmpty()) return true;
		Set<Long> allowedOrganizationIds = availableOrganizations.stream()
				.filter(Objects::nonNull)
				.map(Organization::getId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		return incidentOrganizations.stream()
				.map(Organization::getId)
				.anyMatch(allowedOrganizationIds::contains);
	}

	private boolean canUserSeeAudience(IncidentTimelineAudience audience, User user) {
		return userService.isAdmin(user) || userService.isOperator(user) ||
				audience == IncidentTimelineAudience.CLIENTS || audience == IncidentTimelineAudience.PUBLIC;
	}

	private Set<User> resolveUsers(List<Long> ids) {
		if (ids == null) return new LinkedHashSet<>();
		Map<Long, User> byId = userRepository.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
		return ids.stream().filter(Objects::nonNull).map(byId::get).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Set<ru.ravel.ItDesk.model.Service> resolveServices(List<Long> ids) {
		if (ids == null) return new LinkedHashSet<>();
		Map<Long, ru.ravel.ItDesk.model.Service> byId = serviceRepository.findAllById(ids).stream()
				.collect(Collectors.toMap(ru.ravel.ItDesk.model.Service::getId, Function.identity()));
		return ids.stream().filter(Objects::nonNull).map(byId::get).filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private Set<Organization> resolveOrganizations(List<Long> ids) {
		Set<Organization> result = new LinkedHashSet<>();
		for (Long id : safeLongSet(ids)) {
			userService.assertCurrentUserCanAccessOrganizationId(id);
			result.add(organizationRepository.findById(id).orElseThrow());
		}
		return result;
	}

	private Set<Client> resolveClients(List<Long> ids) {
		Set<Client> result = new LinkedHashSet<>();
		for (Long id : safeLongSet(ids)) {
			Client client = clientRepository.findById(id).orElseThrow();
			userService.assertCurrentUserCanAccessClient(client);
			result.add(client);
		}
		return result;
	}

	private User getUser(Long id) {
		return userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Пользователь не найден: " + id));
	}

	private void saveSystemEntry(SupportIncident incident, String text) {
		incidentTimelineEntryRepository.save(IncidentTimelineEntry.builder()
				.incident(incident)
				.type(IncidentTimelineType.SYSTEM)
				.audience(IncidentTimelineAudience.INTERNAL)
				.text(text)
				.author(currentUserOrNull())
				.createdAt(ZonedDateTime.now())
				.build());
	}

	private User currentUserOrNull() {
		try {
			return userService.getCurrentUser();
		} catch (Exception ignored) {
			return null;
		}
	}

	private boolean isCommunicationOverdue(SupportIncident incident) {
		return incident != null && incident.getStatus() != null && incident.getStatus().requiresCommunicationControl()
				&& incident.getNextUpdateAt() != null && ZonedDateTime.now().isAfter(incident.getNextUpdateAt());
	}

	private long communicationOverdueSeconds(SupportIncident incident) {
		return isCommunicationOverdue(incident) ? Math.max(0, Duration.between(incident.getNextUpdateAt(), ZonedDateTime.now()).getSeconds()) : 0;
	}

	private boolean matchesSearch(SupportIncident incident, String search) {
		return normalizeText(incident.getIncidentNumber()).contains(search)
				|| normalizeText(incident.getTitle()).contains(search)
				|| normalizeText(incident.getDescription()).contains(search)
				|| normalizeText(incident.getImpactSummary()).contains(search)
				|| normalizeText(incident.getServiceName()).contains(search)
				|| incident.getServices().stream().map(ru.ravel.ItDesk.model.Service::getName).map(this::normalizeText).anyMatch(value -> value.contains(search))
				|| incident.getOrganizations().stream().map(Organization::getName).map(this::normalizeText).anyMatch(value -> value.contains(search));
	}

	private String normalizeText(String value) {
		String normalized = Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC)
				.toLowerCase(Locale.ROOT)
				.replace('ё', 'е');
		return normalized.trim();
	}

	private Set<String> tokens(String value) {
		return Arrays.stream(normalizeText(value).split("[^a-zа-я0-9]+"))
				.map(String::trim)
				.filter(token -> token.length() >= 3)
				.filter(token -> !STOP_WORDS.contains(token))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static final Set<String> STOP_WORDS = Set.of("для", "или", "это", "как", "при", "что", "нет", "есть", "the", "and", "with");

	private <E extends Enum<E>> Set<E> parseEnumSet(List<String> values, Class<E> enumClass) {
		if (values == null) return Set.of();
		return values.stream().map(value -> parseEnum(value, enumClass, null)).filter(Objects::nonNull).collect(Collectors.toSet());
	}

	private <E extends Enum<E>> E parseEnum(String value, Class<E> enumClass, E fallback) {
		if (value == null || value.isBlank()) return fallback;
		try {
			return Enum.valueOf(enumClass, value.trim().toUpperCase(Locale.ROOT));
		} catch (Exception ignored) {
			return fallback;
		}
	}

	private Set<Long> safeLongSet(Collection<Long> values) {
		if (values == null) return Set.of();
		return values.stream().filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private int severityRank(IncidentSeverity severity) {
		return severity == null ? 99 : severity.ordinal();
	}

	private Long idOf(User user) {
		return user == null ? null : user.getId();
	}

	private String userDisplayName(User user) {
		if (user == null) return "—";
		String fullName = (Objects.toString(user.getLastname(), "") + " " + Objects.toString(user.getFirstname(), "")).trim();
		return fullName.isBlank() ? Objects.toString(user.getUsername(), "") : fullName;
	}

	private Map<String, Object> userDto(User user) {
		if (user == null) return null;
		return Map.of(
				"id", user.getId(),
				"firstname", Objects.toString(user.getFirstname(), ""),
				"lastname", Objects.toString(user.getLastname(), ""),
				"username", Objects.toString(user.getUsername(), ""),
				"displayName", userDisplayName(user)
		);
	}

	private Map<String, Object> organizationDto(Organization organization) {
		if (organization == null) return null;
		return Map.of("id", organization.getId(), "name", Objects.toString(organization.getName(), ""));
	}

	private Map<String, Object> clientDto(Client client) {
		if (client == null) return null;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", client.getId());
		result.put("firstname", client.getFirstname());
		result.put("lastname", client.getLastname());
		result.put("email", client.getEmail());
		result.put("phoneNumber", client.getPhoneNumber());
		result.put("displayName", (Objects.toString(client.getLastname(), "") + " " + Objects.toString(client.getFirstname(), "")).trim());
		result.put("organization", organizationDto(client.getOrganization()));
		return result;
	}

	private Map<String, Object> serviceDto(ru.ravel.ItDesk.model.Service service) {
		if (service == null) return null;
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", service.getId());
		result.put("code", service.getCode());
		result.put("name", Objects.toString(service.getName(), ""));
		result.put("active", service.getActive());
		result.put("monitoringStatus", service.getMonitoringStatus());
		return result;
	}

	private Map<String, Object> supportLineDto(SupportLine line) {
		if (line == null) return null;
		return Map.of("id", line.getId(), "name", Objects.toString(line.getName(), ""), "level", Objects.requireNonNullElse(line.getLevel(), 0));
	}

	private Map<String, Object> template(String key, String title, String text) {
		return Map.of("key", key, "title", title, "text", text);
	}


	private long secondsBetween(ZonedDateTime start, ZonedDateTime end) {
		if (start == null || end == null || end.isBefore(start)) return 0;
		return Duration.between(start, end).getSeconds();
	}

	@SafeVarargs
	private final <T> T firstNonNull(T... values) {
		for (T value : values) if (value != null) return value;
		return null;
	}

	private String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private boolean hasAuthority(User user, String role) {
		if (user == null || user.getAuthorities() == null) return false;
		String expected = "ROLE_" + role;
		return user.getAuthorities().stream()
				.filter(Objects::nonNull)
				.map(Object::toString)
				.anyMatch(authority -> authority.equals(role) || authority.equals(expected));
	}

	private void addIfNotNull(Set<User> users, User user) {
		if (user != null) users.add(user);
	}
}

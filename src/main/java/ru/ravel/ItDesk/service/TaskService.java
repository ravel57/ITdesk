package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.time.*;
import java.util.*;


@Service
@RequiredArgsConstructor
public class TaskService {

	private final TaskRepository taskRepository;
	private final TaskTypeRepository taskTypeRepository;
	private final ClientRepository clientsRepository;
	private final MessageRepository messageRepository;
	private final OrganizationService organizationService;
	private final SlaRepository slaRepository;
	private final GlobalSearchService globalSearchService;
	private final EventPublisher eventPublisher;
	private final WebSocketService webSocketService;
	private final SlaPauseRepository slaPauseRepository;
	private final AppSettingsRepository appSettingsRepository;
	private final UserService userService;

	private static final String FREEZE_SLA_PAUSE_REASON = "Заявка заморожена";
	private static final String CLOSED_SLA_PAUSE_REASON = "Заявка закрыта";
	private static final String MANUAL_SLA_PAUSE_REASON = "Ручная пауза SLA";
	private static final String AUTO_NON_WORKING_TIME_SLA_PAUSE_REASON = "Авто-пауза SLA: нерабочее время";
	private final UserNotificationService userNotificationService;


	@Transactional(readOnly = true)
	public List<TaskType> getTaskTypes() {
		return taskTypeRepository.findAll().stream().sorted().toList();
	}


	@Transactional(readOnly = true)
	public TaskType getTaskType(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		return taskTypeRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(id)));
	}


	@Transactional
	public Task newTask(Long clientId, Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		Client client = getClientForCurrentUser(clientId);
		return createTaskForClient(client, task);
	}


	@Transactional
	public Task newTaskForSystem(Long clientId, Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		return createTaskForClient(client, task);
	}


	@Transactional
	public Task updateTask(Long clientId, Task task) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (task == null) {
			throw new IllegalArgumentException("task must not be null");
		}
		if (task.getId() == null) {
			throw new IllegalArgumentException("task.id must not be null");
		}
		prepareTaskBeforeSave(task);
		Task olderTask = taskRepository.findById(task.getId()).orElseThrow();
		Client client = getClientForCurrentUser(clientId);
		FrozenStatus frozenStatus = FrozenStatus.getInstance();
		CompletedStatus completedStatus = CompletedStatus.getInstance();
		Priority oldPriority = olderTask.getPriority();
		Status oldStatus = olderTask.getStatus();
		Status statusBeforeUpdate = olderTask.getStatus();
		Status requestedStatus = task.getStatus();
		boolean requestedStatusIsFrozen = isSameStatus(requestedStatus, frozenStatus);
		boolean requestedStatusIsCompleted = isSameStatus(requestedStatus, completedStatus);
		boolean requestedStatusActuallyChanged = requestedStatus != null && !isSameStatus(oldStatus, requestedStatus);
		boolean oldFrozen = Boolean.TRUE.equals(olderTask.getFrozen());
		User oldExecutor = olderTask.getExecutor();
		Boolean oldCompleted = olderTask.getCompleted();
		ZonedDateTime oldDeadline = olderTask.getDeadline();
		ZonedDateTime oldFrozenUntil = olderTask.getFrozenUntil();
		Set<Tag> oldTags = new HashSet<>(Objects.requireNonNullElse(olderTask.getTags(), Collections.emptyList()));
		List<Map<String, Object>> changes = new ArrayList<>();
		String statusChangeReason = normalizeStatusChangeReason(task.getStatusChangeReason());
		addChange(changes, "name", "Название", olderTask.getName(), task.getName());
		addChange(changes, "description", "Описание", olderTask.getDescription(), task.getDescription());
		addChange(changes, "type", "Тип", getTaskTypeName(olderTask.getType()), getTaskTypeName(task.getType()));
		addChange(changes, "checklist", "Чек-лист", checklistToHistoryValue(olderTask.getChecklist()), checklistToHistoryValue(task.getChecklist()));
		addChange(changes, "deadline", "Дедлайн", olderTask.getDeadline(), task.getDeadline());
		addChange(changes, "executor", "Исполнитель", getUserDisplayName(olderTask.getExecutor()), getUserDisplayName(task.getExecutor()));
		addChange(changes, "priority", "Приоритет", getName(olderTask.getPriority()), getName(task.getPriority()));
		addChange(changes, "status", "Статус", getName(olderTask.getStatus()), getName(task.getStatus()));
		addChange(changes, "completed", "Закрыта", olderTask.getCompleted(), task.getCompleted());
		addChange(changes, "linkedMessageId", "Связанное сообщение", olderTask.getLinkedMessageId(), task.getLinkedMessageId());
		olderTask.setName(task.getName());
		olderTask.setDescription(task.getDescription());
		olderTask.setType(task.getType());
		olderTask.setChecklist(task.getChecklist());
		olderTask.setPriority(task.getPriority());
		if (olderTask.getSla() == null || !Objects.equals(oldPriority, task.getPriority())) {
			setSla(client, olderTask);
		}
		boolean reopening = Boolean.TRUE.equals(olderTask.getCompleted()) && Boolean.FALSE.equals(task.getCompleted());
		olderTask.setDeadline(task.getDeadline());
		olderTask.setExecutor(task.getExecutor());
		olderTask.setTags(task.getTags());
		olderTask.setLinkedMessageId(task.getLinkedMessageId());
		if (task.getFrozen() != null) {
			if (task.getFrozen()) {
				olderTask.setFrozen(true);
				olderTask.setFrozenFrom(
						task.getFrozenFrom() != null
								? task.getFrozenFrom()
								: Objects.requireNonNullElse(olderTask.getFrozenFrom(), ZonedDateTime.now())
				);
				olderTask.setFrozenUntil(task.getFrozenUntil());
			} else {
				olderTask.setFrozen(false);
				olderTask.setFrozenFrom(null);
				olderTask.setFrozenUntil(null);
			}
		} else if (oldFrozen && requestedStatus != null && !requestedStatusIsFrozen) {
			olderTask.setFrozen(false);
			olderTask.setFrozenFrom(null);
			olderTask.setFrozenUntil(null);
		} else if (!oldFrozen && requestedStatusIsFrozen) {
			// Нельзя молча переводить заявку в статус «Заморожена» без срока заморозки.
			// Заморозка должна приходить отдельным действием с frozen=true и frozenUntil.
			task.setStatus(oldStatus);
			requestedStatus = oldStatus;
			requestedStatusIsFrozen = isSameStatus(requestedStatus, frozenStatus);
			requestedStatusIsCompleted = isSameStatus(requestedStatus, completedStatus);
			requestedStatusActuallyChanged = false;
		}
		olderTask.setCompleted(Boolean.TRUE.equals(task.getCompleted()));
		if (!Objects.equals(oldCompleted, task.getCompleted())) {
			if (Boolean.TRUE.equals(task.getCompleted())) {
				olderTask.setClosedAt(ZonedDateTime.now());
			} else {
				olderTask.setClosedAt(null);
			}
		}
		if (reopening) {
			olderTask.setStatus(
					olderTask.getPreviousStatus() != null
							&& !Objects.equals(olderTask.getPreviousStatus(), completedStatus)
							&& !Objects.equals(olderTask.getPreviousStatus(), frozenStatus)
							? olderTask.getPreviousStatus()
							: task.getStatus()
			);
		} else {
			olderTask.setStatus(task.getStatus());
		}
		if (requestedStatusActuallyChanged
				&& task.getPreviousStatus() != null
				&& !isSameStatus(task.getPreviousStatus(), completedStatus)
				&& !isSameStatus(task.getPreviousStatus(), frozenStatus)) {
			olderTask.setPreviousStatus(task.getPreviousStatus());
		}
		if (Boolean.TRUE.equals(olderTask.getFrozen())) {
			if (requestedStatusActuallyChanged) {
				if (!isSameStatus(statusBeforeUpdate, frozenStatus) && !isSameStatus(statusBeforeUpdate, completedStatus)) {
					olderTask.setPreviousStatus(statusBeforeUpdate);
				} else if (!isSameStatus(olderTask.getStatus(), frozenStatus)
						&& !isSameStatus(olderTask.getStatus(), completedStatus)) {
					olderTask.setPreviousStatus(olderTask.getStatus());
				}
			}
			olderTask.setStatus(frozenStatus);
			olderTask.setFrozen(true);
		} else if (olderTask.getPreviousStatus() != null
				&& Objects.equals(frozenStatus.getId(), olderTask.getStatus() == null ? null : olderTask.getStatus().getId())
				&& Objects.equals(olderTask.getStatus(), frozenStatus)) {
			olderTask.setStatus(olderTask.getPreviousStatus());
		}
		if (Boolean.TRUE.equals(olderTask.getCompleted())) {
			if (!Objects.equals(olderTask.getStatus(), completedStatus)
					&& !Objects.equals(olderTask.getStatus(), frozenStatus)) {
				olderTask.setPreviousStatus(olderTask.getStatus());
			}
			if (Boolean.TRUE.equals(olderTask.getFrozen())) {
				olderTask.setFrozen(false);
				olderTask.setFrozenFrom(null);
				olderTask.setFrozenUntil(null);
			}
			olderTask.setStatus(completedStatus);
			olderTask.setCompleted(true);
		} else if (olderTask.getPreviousStatus() != null
				&& Boolean.FALSE.equals(olderTask.getFrozen())
				&& !Objects.equals(olderTask.getPreviousStatus(), completedStatus)
				&& Objects.equals(olderTask.getStatus(), completedStatus)) {
			olderTask.setStatus(olderTask.getPreviousStatus());
		} else if (Objects.equals(olderTask.getStatus(), completedStatus)
				&& Objects.equals(olderTask.getStatus() == null ? null : olderTask.getStatus().getId(), completedStatus.getId())) {
			olderTask.setCompleted(false);
			olderTask.setStatus(olderTask.getPreviousStatus());
		}
		boolean newFrozen = Boolean.TRUE.equals(olderTask.getFrozen());
		if (oldFrozen != newFrozen) {
			addChange(
					changes,
					"frozen",
					"Заморозка",
					oldFrozen ? "Да" : "Нет",
					newFrozen ? "Да" : "Нет"
			);
		}
		if (!Objects.equals(oldFrozenUntil, olderTask.getFrozenUntil())) {
			addChange(
					changes,
					"frozenUntil",
					"Заморожена до",
					oldFrozenUntil,
					olderTask.getFrozenUntil()
			);
		}
		boolean statusChanged = !isSameStatus(oldStatus, olderTask.getStatus());
		boolean completedChanged = !Objects.equals(oldCompleted, olderTask.getCompleted());
		boolean frozenChanged = oldFrozen != newFrozen;
		boolean oldSlaPausedByTaskState = oldFrozen || Boolean.TRUE.equals(oldCompleted);
		boolean newSlaPausedByTaskState = newFrozen || Boolean.TRUE.equals(olderTask.getCompleted());
		if (oldSlaPausedByTaskState != newSlaPausedByTaskState) {
			addChange(
					changes,
					"slaPause",
					"SLA-пауза",
					oldSlaPausedByTaskState ? "Поставлена на паузу" : "Снята с паузы",
					newSlaPausedByTaskState ? "Поставлена на паузу" : "Снята с паузы"
			);
		}
		if (statusChangeReason != null && (statusChanged || completedChanged || frozenChanged)) {
			olderTask.setStatusChangeReason(statusChangeReason);
		}
		syncSlaPauseState(olderTask);
		olderTask.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(olderTask);
		globalSearchService.indexClient(client);
		globalSearchService.indexTask(client, savedTask);
		if (!changes.isEmpty()) {
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedTask,
					"client", client,
					"changes", changes
			));
		}
		if (!isSameStatus(oldStatus, savedTask.getStatus())) {
			eventPublisher.publish(TriggerType.TASK_STATUS_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldStatus", oldStatus,
					"newStatus", savedTask.getStatus(),
					"reason", statusChangeReason
			));
		}
		if (!Objects.equals(oldPriority, savedTask.getPriority())) {
			eventPublisher.publish(TriggerType.TASK_PRIORITY_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldPriority", oldPriority,
					"newPriority", savedTask.getPriority()
			));
		}
		if (!Objects.equals(oldExecutor, savedTask.getExecutor())) {
			eventPublisher.publish(TriggerType.TASK_ASSIGNEE_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldExecutor", oldExecutor,
					"newExecutor", savedTask.getExecutor()
			));
			if (savedTask.getExecutor() != null) {
				userNotificationService.send(new UserNotification(
						UserNotificationEvent.NEW_TASK,
						savedTask.getName(),
						savedTask.getExecutor().getId()
				));
			}
		}
		if (!Objects.equals(oldDeadline, savedTask.getDeadline())) {
			eventPublisher.publish(TriggerType.TASK_DUE_DATE_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldDeadline", oldDeadline,
					"newDeadline", savedTask.getDeadline()
			));
		}

		if (!Objects.equals(oldCompleted, savedTask.getCompleted()) && Boolean.TRUE.equals(savedTask.getCompleted())) {
			eventPublisher.publish(TriggerType.TASK_CLOSED, eventPayload(
					"task", savedTask,
					"client", client,
					"reason", statusChangeReason
			));
		}
		if (Boolean.TRUE.equals(oldCompleted) && !savedTask.getCompleted()) {
			eventPublisher.publish(TriggerType.TASK_REOPENED, eventPayload(
					"task", savedTask,
					"client", client,
					"reason", statusChangeReason
			));
		}
		Set<Tag> newTags = new HashSet<>(Objects.requireNonNullElse(savedTask.getTags(), Collections.emptyList()));
		for (Tag tag : newTags) {
			if (!oldTags.contains(tag)) {
				eventPublisher.publish(TriggerType.TASK_TAG_ADDED, eventPayload(
						"task", savedTask,
						"client", client,
						"tag", tag
				));
			}
		}
		for (Tag tag : oldTags) {
			if (!newTags.contains(tag)) {
				eventPublisher.publish(TriggerType.TASK_TAG_REMOVED, eventPayload(
						"task", savedTask,
						"client", client,
						"tag", tag
				));
			}
		}
		return savedTask;
	}


	private void setSla(Client client, Task task) {
		if (task == null || task.getPriority() == null) {
			return;
		}
		Map<Organization, Map<Priority, SlaValue>> slaByPriority = organizationService.getSlaByPriority();
		SlaValue slaValue = findSlaValue(
				slaByPriority,
				client == null ? null : client.getOrganization(),
				task.getPriority()
		);
		if (slaValue == null) {
			slaValue = findDefaultSlaValue(slaByPriority, task.getPriority());
		}
		Duration slaDuration = slaValue == null
				? Duration.ZERO
				: slaValue.toDuration(getWorkdayDuration());

		if (slaDuration.isZero() || slaDuration.isNegative()) {
			task.setSla(null);
			return;
		}
		Sla sla = task.getSla();
		if (sla == null) {
			sla = Sla.builder()
					.startDate(Objects.requireNonNullElse(task.getCreatedAt(), ZonedDateTime.now()))
					.build();
		}
		sla.setDuration(slaDuration);
		slaRepository.save(sla);
		task.setSla(sla);
	}


	private Duration getWorkdayDuration() {
		return appSettingsRepository.findAll().stream()
				.findFirst()
				.map(AppSettings::getWorkdayDuration)
				.orElse(Duration.ofHours(24));
	}


	private boolean shouldBlockManualResumeBecauseOfAutoNonWorkingTime() {
		AppSettings settings = appSettingsRepository.findAll().stream()
				.findFirst()
				.orElse(null);
		if (settings == null || !Boolean.TRUE.equals(settings.getWorkingTimeEnabled())) {
			return false;
		}
		try {
			ZoneId zoneId = settings.getTimezone() == null || settings.getTimezone().isBlank()
					? ZoneId.systemDefault()
					: ZoneId.of(settings.getTimezone());
			ZonedDateTime now = ZonedDateTime.now(zoneId);
			if (!isWorkingDayEnabled(settings, now.getDayOfWeek())) {
				return true;
			}
			LocalTime start = LocalTime.parse(settings.getWorkdayStart());
			LocalTime end = LocalTime.parse(settings.getWorkdayEnd());
			return !isInsideWorkingTime(now.toLocalTime(), start, end);
		} catch (Exception e) {
			return true;
		}
	}


	private boolean isInsideWorkingTime(LocalTime time, LocalTime start, LocalTime end) {
		if (start.equals(end)) {
			return true;
		}
		if (end.isAfter(start)) {
			return !time.isBefore(start) && time.isBefore(end);
		}
		return !time.isBefore(start) || time.isBefore(end);
	}


	private boolean isWorkingDayEnabled(AppSettings settings, DayOfWeek dayOfWeek) {
		return switch (dayOfWeek) {
			case MONDAY -> Boolean.TRUE.equals(settings.getMondayEnabled());
			case TUESDAY -> Boolean.TRUE.equals(settings.getTuesdayEnabled());
			case WEDNESDAY -> Boolean.TRUE.equals(settings.getWednesdayEnabled());
			case THURSDAY -> Boolean.TRUE.equals(settings.getThursdayEnabled());
			case FRIDAY -> Boolean.TRUE.equals(settings.getFridayEnabled());
			case SATURDAY -> Boolean.TRUE.equals(settings.getSaturdayEnabled());
			case SUNDAY -> Boolean.TRUE.equals(settings.getSundayEnabled());
		};
	}


	private SlaValue findSlaValue(Map<Organization, Map<Priority, SlaValue>> slaByPriority, Organization organization, Priority priority) {
		if (organization == null || priority == null) {
			return null;
		}
		Map<Priority, SlaValue> organizationSla = slaByPriority.entrySet().stream()
				.filter(entry -> Objects.equals(entry.getKey().getId(), organization.getId()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
		if (organizationSla == null) {
			return null;
		}
		return organizationSla.entrySet().stream()
				.filter(entry -> Objects.equals(entry.getKey().getId(), priority.getId()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
	}


	private SlaValue findDefaultSlaValue(Map<Organization, Map<Priority, SlaValue>> slaByPriority, Priority priority) {
		if (priority == null) {
			return null;
		}
		Map<Priority, SlaValue> defaultSla = slaByPriority.entrySet().stream()
				.filter(entry -> entry.getKey() instanceof DefaultOrganization)
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
		if (defaultSla == null) {
			return null;
		}
		return defaultSla.entrySet().stream()
				.filter(entry -> Objects.equals(entry.getKey().getId(), priority.getId()))
				.map(Map.Entry::getValue)
				.findFirst()
				.orElse(null);
	}


	private static Map<String, Object> eventPayload(Object... values) {
		if (values.length % 2 != 0) {
			throw new IllegalArgumentException("eventPayload requires key-value pairs");
		}
		Map<String, Object> payload = new HashMap<>();
		for (int i = 0; i < values.length; i += 2) {
			Object key = values[i];
			if (!(key instanceof String)) {
				throw new IllegalArgumentException("eventPayload key must be String");
			}
			payload.put((String) key, values[i + 1]);
		}
		return payload;
	}


	private static void addChange(List<Map<String, Object>> changes, String field, String label, Object oldValue, Object newValue) {
		if (Objects.equals(oldValue, newValue)) {
			return;
		}
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("field", field);
		change.put("label", label);
		change.put("oldValue", Objects.toString(oldValue, ""));
		change.put("newValue", Objects.toString(newValue, ""));
		changes.add(change);
	}



	private static List<Map<String, Object>> buildTaskCreatedChanges(Task task) {
		List<Map<String, Object>> changes = new ArrayList<>();
		if (task == null) {
			return changes;
		}
		addCreatedField(changes, "name", "Название", task.getName());
		addCreatedField(changes, "description", "Описание", task.getDescription());
		addCreatedField(changes, "type", "Тип", getTaskTypeName(task.getType()));
		addCreatedField(changes, "checklist", "Чек-лист", checklistToHistoryValue(task.getChecklist()));
		addCreatedField(changes, "deadline", "Дедлайн", task.getDeadline());
		addCreatedField(changes, "executor", "Исполнитель", getUserDisplayName(task.getExecutor()));
		addCreatedField(changes, "priority", "Приоритет", getName(task.getPriority()));
		addCreatedField(changes, "status", "Статус", getName(task.getStatus()));
		addCreatedField(changes, "tags", "Теги", tagsToHistoryValue(task.getTags()));
		addCreatedField(changes, "linkedMessageId", "Связанное сообщение", task.getLinkedMessageId());
		if (Boolean.TRUE.equals(task.getCompleted())) {
			addCreatedField(changes, "completed", "Закрыта", "Да");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			addCreatedField(changes, "frozen", "Заморозка", "Да");
			addCreatedField(changes, "frozenUntil", "Заморожена до", task.getFrozenUntil());
		}
		return changes;
	}


	private static void addCreatedField(List<Map<String, Object>> changes, String field, String label, Object value) {
		String normalizedValue = Objects.toString(value, "").trim();
		if (normalizedValue.isBlank()) {
			return;
		}
		Map<String, Object> change = new LinkedHashMap<>();
		change.put("field", field);
		change.put("label", label);
		change.put("oldValue", "—");
		change.put("newValue", normalizedValue);
		changes.add(change);
	}


	private static String tagsToHistoryValue(List<Tag> tags) {
		if (tags == null || tags.isEmpty()) {
			return "";
		}
		return tags.stream()
				.filter(Objects::nonNull)
				.map(Tag::getName)
				.filter(Objects::nonNull)
				.map(String::trim)
				.filter(name -> !name.isBlank())
				.toList()
				.toString();
	}

	private static String getName(Object object) {
		return switch (object) {
			case null -> "";
			case Status status -> status.getName();
			case Priority priority -> priority.getName();
			case Tag tag -> tag.getName();
			default -> Objects.toString(object, "");
		};
	}


	private static boolean isSameStatus(Status left, Status right) {
		if (left == null || right == null) {
			return false;
		}
		if (left.getId() != null && right.getId() != null) {
			return Objects.equals(left.getId(), right.getId());
		}
		return Objects.equals(left, right)
				|| Objects.equals(getName(left), getName(right));
	}


	private static String getUserDisplayName(User user) {
		if (user == null) {
			return "";
		}
		String lastname = Objects.toString(user.getLastname(), "").trim();
		String firstname = Objects.toString(user.getFirstname(), "").trim();
		String fullName = ("%s %s".formatted(lastname, firstname)).trim();

		if (!fullName.isBlank()) {
			return fullName;
		}
		return Objects.toString(user.getUsername(), "");
	}


	private static String getTaskTypeName(TaskType taskType) {
		if (taskType == null) {
			return "";
		}
		return Objects.toString(taskType.getType(), "");
	}


	private static String checklistToHistoryValue(List<ChecklistItem> checklist) {
		if (checklist == null || checklist.isEmpty()) {
			return "";
		}
		return checklist.stream()
				.filter(item -> item != null && item.getText() != null && !item.getText().isBlank())
				.map(item -> "%s%s".formatted(
						Boolean.TRUE.equals(item.getCompleted()) ? "[x] " : "[ ] ",
						item.getText().trim()
				))
				.toList()
				.toString();
	}


	@Transactional
	public TaskType createTaskType(TaskType taskType) {
		if (taskType == null) {
			throw new IllegalArgumentException("taskType must not be null");
		}
		String type = normalizeTaskTypeName(taskType.getType());
		if (taskTypeRepository.existsByType(type)) {
			throw new IllegalArgumentException("Тип заявки уже существует: %s".formatted(type));
		}
		boolean makeDefault = Boolean.TRUE.equals(taskType.getDefaultSelection()) || !hasDefaultTaskType();
		TaskType savedTaskType = taskTypeRepository.save(TaskType.builder()
				.type(type)
				.orderNumber(getTaskTypes().size() + 1)
				.defaultSelection(makeDefault)
				.checklistTemplate(normalizeChecklist(taskType.getChecklistTemplate()))
				.autoApplyChecklist(!Boolean.FALSE.equals(taskType.getAutoApplyChecklist()))
				.build());
		if (makeDefault) {
			return taskTypeSetDefaultSelection(savedTaskType);
		}
		return savedTaskType;
	}


	@Transactional
	public TaskType updateTaskType(Long id, TaskType request) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		if (request == null) {
			throw new IllegalArgumentException("taskType must not be null");
		}
		TaskType taskType = taskTypeRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(id)));
		String type = normalizeTaskTypeName(request.getType());
		taskTypeRepository.findByType(type)
				.filter(found -> !found.getId().equals(id))
				.ifPresent(found -> {
					throw new IllegalArgumentException("Тип заявки уже существует: %s".formatted(type));
				});
		taskType.setType(type);
		taskType.setChecklistTemplate(normalizeChecklist(request.getChecklistTemplate()));
		taskType.setAutoApplyChecklist(!Boolean.FALSE.equals(request.getAutoApplyChecklist()));
		TaskType savedTaskType = taskTypeRepository.save(taskType);
		if (Boolean.TRUE.equals(request.getDefaultSelection())) {
			return taskTypeSetDefaultSelection(savedTaskType);
		}
		return savedTaskType;
	}


	@Transactional
	public List<TaskType> resortTaskTypes(List<TaskType> newOrderedTaskTypes) {
		if (newOrderedTaskTypes == null) {
			return getTaskTypes();
		}
		List<TaskType> taskTypes = taskTypeRepository.findAll();
		for (TaskType taskType : taskTypes) {
			int orderNumber = newOrderedTaskTypes.indexOf(taskType);
			if (orderNumber >= 0) {
				taskType.setOrderNumber(orderNumber);
			}
		}
		taskTypeRepository.saveAll(taskTypes);
		return taskTypes.stream().sorted().toList();
	}


	@Transactional
	public void deleteTaskType(Long id) {
		if (id == null) {
			throw new IllegalArgumentException("id must not be null");
		}
		TaskType taskType = taskTypeRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(id)));
		List<Task> tasksWithDeletedType = taskRepository.findAllByTypeId(id);
		for (Task task : tasksWithDeletedType) {
			TaskType oldType = task.getType();
			task.setType(null);
			task.setLastActivity(ZonedDateTime.now());
			Task savedTask = taskRepository.save(task);
			Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
			if (client != null) {
				globalSearchService.indexClient(client);
				globalSearchService.indexTask(client, savedTask);
			}
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedTask,
					"client", client,
					"changes", List.of(Map.of(
							"field", "type",
							"label", "Тип",
							"oldValue", getTaskTypeName(oldType),
							"newValue", ""
					))
			));
		}
		boolean wasDefault = Boolean.TRUE.equals(taskType.getDefaultSelection());
		taskTypeRepository.delete(taskType);
		if (wasDefault) {
			taskTypeRepository.findAll().stream()
					.sorted()
					.findFirst()
					.ifPresent(this::taskTypeSetDefaultSelection);
		}
	}


	@Transactional
	public Task saveTask(Task task) {
		task.setType(resolveTaskType(task.getType()));
		task.setChecklist(normalizeChecklist(task.getChecklist()));
		return taskRepository.save(task);
	}


	public void prepareTaskBeforeSave(Task task) {
		if (task == null) {
			return;
		}
		task.setType(resolveTaskType(task.getType()));
		task.setChecklist(normalizeChecklist(task.getChecklist()));
	}


	TaskType resolveTaskType(TaskType requestType) {
		if (requestType == null) {
			return getDefaultTaskType();
		}
		if (requestType.getId() != null) {
			return taskTypeRepository.findById(requestType.getId())
					.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(requestType.getId())));
		}
		if (requestType.getType() != null && !requestType.getType().isBlank()) {
			String type = normalizeTaskTypeName(requestType.getType());
			return taskTypeRepository.findByType(type)
					.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %s".formatted(type)));
		}
		return getDefaultTaskType();
	}


	private TaskType getDefaultTaskType() {
		return taskTypeRepository.findAll().stream()
				.filter(taskType -> Boolean.TRUE.equals(taskType.getDefaultSelection()))
				.sorted()
				.findFirst()
				.orElseGet(() -> taskTypeRepository.findByType("Запрос")
						.map(this::taskTypeSetDefaultSelection)
						.orElseGet(() -> taskTypeSetDefaultSelection(taskTypeRepository.save(TaskType.builder()
								.type("Запрос")
								.orderNumber(getTaskTypes().size() + 1)
								.defaultSelection(true)
								.autoApplyChecklist(true)
								.checklistTemplate(new ArrayList<>())
								.build()))));
	}


	List<ChecklistItem> normalizeChecklist(List<ChecklistItem> checklist) {
		if (checklist == null) {
			return new ArrayList<>();
		}
		return checklist.stream()
				.filter(item -> item != null && item.getText() != null && !item.getText().isBlank())
				.map(item -> ChecklistItem.builder()
						.id(item.getId() != null && !item.getId().isBlank()
								? item.getId()
								: UUID.randomUUID().toString())
						.text(item.getText().trim())
						.completed(Boolean.TRUE.equals(item.getCompleted()))
						.build())
				.toList();
	}


	private String normalizeTaskTypeName(String type) {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("Название типа заявки не может быть пустым");
		}
		return type.trim();
	}


	private void syncSlaPauseState(Task task) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		boolean nowFrozen = Boolean.TRUE.equals(task.getFrozen());
		boolean nowCompleted = Boolean.TRUE.equals(task.getCompleted());
		if (nowFrozen) {
			closeAllSlaPausesExceptReason(task, FREEZE_SLA_PAUSE_REASON);
			if (!hasActiveSlaPauseByReason(task, FREEZE_SLA_PAUSE_REASON)) {
				openSlaPause(
						task,
						FREEZE_SLA_PAUSE_REASON,
						task.getFrozenFrom() != null ? task.getFrozenFrom() : ZonedDateTime.now()
				);
			}
			return;
		}
		if (nowCompleted) {
			closeAllSlaPausesExceptReason(task, CLOSED_SLA_PAUSE_REASON);
			if (!hasActiveSlaPauseByReason(task, CLOSED_SLA_PAUSE_REASON)) {
				openSlaPause(
						task,
						CLOSED_SLA_PAUSE_REASON,
						task.getClosedAt() != null ? task.getClosedAt() : ZonedDateTime.now()
				);
			}
			return;
		}
		closeSlaPause(task, FREEZE_SLA_PAUSE_REASON);
		closeSlaPause(task, CLOSED_SLA_PAUSE_REASON);
	}


	private boolean hasActiveSlaPauseByReason(Task task, String reason) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return false;
		}
		return slaPauseRepository
				.findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId(), reason)
				.isPresent();
	}


	private void openSlaPause(Task task, String reason, ZonedDateTime startedAt) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}

		slaPauseRepository
				.findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(
						task.getSla().getId(),
						reason
				)
				.ifPresentOrElse(
						pause -> {
							// Уже есть активная пауза с этой причиной.
						},
						() -> {
							SlaPause pause = SlaPause.builder()
									.sla(task.getSla())
									.startedAt(startedAt != null ? startedAt : ZonedDateTime.now())
									.endedAt(null)
									.reason(reason)
									.build();
							slaPauseRepository.save(pause);
						}
				);
	}


	private void closeSlaPause(Task task, String reason) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId(), reason);
		if (activePauses.isEmpty()) {
			return;
		}
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
	}


	@Transactional
	public void autoUnfreezeTask(Long taskId) {
		if (taskId == null) {
			return;
		}
		Task task = taskRepository.findById(taskId).orElse(null);
		if (task == null || !Boolean.TRUE.equals(task.getFrozen())) {
			return;
		}
		Client client = clientsRepository.findByTaskId(task.getId()).orElse(null);
		Status oldStatus = task.getStatus();
		boolean oldFrozen = Boolean.TRUE.equals(task.getFrozen());
		task.setFrozen(false);
		task.setFrozenFrom(null);
		task.setFrozenUntil(null);
		if (task.getPreviousStatus() != null) {
			task.setStatus(task.getPreviousStatus());
		}
		boolean newFrozen = Boolean.TRUE.equals(task.getFrozen());
		List<Map<String, Object>> changes = new ArrayList<>();
		if (oldFrozen != newFrozen) {
			addChange(
					changes,
					"frozen",
					"Заморозка",
					oldFrozen ? "Да" : "Нет",
					newFrozen ? "Да" : "Нет"
			);
			addChange(
					changes,
					"slaPause",
					"SLA-пауза",
					oldFrozen ? "Поставлена на паузу" : "Снята с паузы",
					newFrozen ? "Поставлена на паузу" : "Снята с паузы"
			);
		}
		syncSlaPauseState(task);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		if (client != null) {
			globalSearchService.indexClient(client);
			globalSearchService.indexTask(client, savedTask);
		}
		if (!changes.isEmpty()) {
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", savedTask,
					"client", client,
					"changes", changes
			));
		}
		if (!isSameStatus(oldStatus, savedTask.getStatus())) {
			eventPublisher.publish(TriggerType.TASK_STATUS_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldStatus", oldStatus,
					"newStatus", savedTask.getStatus()
			));
		}
	}


	@Transactional
	public Task pauseTaskSla(Long clientId, Long taskId, String reason) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка уже заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка закрыта");
		}
		boolean alreadyPaused = slaPauseRepository
				.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId())
				.isPresent();
		if (alreadyPaused) {
			return task;
		}
		String pauseReason = reason == null || reason.isBlank()
				? MANUAL_SLA_PAUSE_REASON
				: reason.trim();
		SlaPause pause = SlaPause.builder()
				.sla(task.getSla())
				.startedAt(ZonedDateTime.now())
				.endedAt(null)
				.reason(pauseReason)
				.build();
		slaPauseRepository.save(pause);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		publishManualSlaPauseHistory(client, savedTask, true, pauseReason);
		return savedTask;
	}


	@Transactional
	public Task resumeTaskSla(Long clientId, Long taskId) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		Task task = taskRepository.findById(taskId).orElseThrow();
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка закрыта");
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndEndedAtIsNull(task.getSla().getId());
		if (activePauses.isEmpty()) {
			return task;
		}
		boolean hasAutoNonWorkingTimePause = activePauses.stream()
				.anyMatch(pause -> Objects.equals(
						pause.getReason(),
						AUTO_NON_WORKING_TIME_SLA_PAUSE_REASON
				));

		if (hasAutoNonWorkingTimePause && shouldBlockManualResumeBecauseOfAutoNonWorkingTime()) {
			throw new IllegalStateException("Нельзя вручную снять SLA с авто-паузы: сейчас нерабочее время");
		}
		String reason = activePauses.getFirst().getReason();
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		publishManualSlaPauseHistory(client, savedTask, false, reason);
		return savedTask;
	}


	private void publishManualSlaPauseHistory(Client client, Task task, boolean paused, String reason) {
		List<Map<String, Object>> changes = new ArrayList<>();
		addChange(
				changes,
				"slaPause",
				"SLA-пауза",
				paused ? "Снята с паузы" : "Поставлена на паузу",
				paused ? "Поставлена на паузу" : "Снята с паузы"
		);
		if (reason != null && !reason.isBlank()) {
			addChange(
					changes,
					"slaPauseReason",
					"Причина паузы",
					"—",
					reason
			);
		}
		if (!changes.isEmpty()) {
			eventPublisher.publish(TriggerType.TASK_UPDATED, eventPayload(
					"task", task,
					"client", client,
					"changes", changes
			));
		}
		if (client != null) {
			globalSearchService.indexClient(client);
			globalSearchService.indexTask(client, task);
		}
	}


	@Transactional
	public Task pauseTaskSla(Long taskId, String reason) {
		getClientByTaskForCurrentUser(taskId);
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Task task = taskRepository.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + taskId));
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную поставить SLA на паузу: заявка закрыта");
		}
		boolean alreadyPaused = slaPauseRepository
				.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId())
				.isPresent();
		if (alreadyPaused) {
			return task;
		}
		String pauseReason = (reason == null || reason.isBlank())
				? "Ручная пауза SLA"
				: reason.trim();
		SlaPause pause = SlaPause.builder()
				.sla(task.getSla())
				.startedAt(ZonedDateTime.now())
				.endedAt(null)
				.reason(pauseReason)
				.build();
		slaPauseRepository.save(pause);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
		publishManualSlaPauseHistory(client, savedTask, true, pauseReason);
		return savedTask;
	}


	@Transactional
	public Task resumeTaskSla(Long taskId) {
		getClientByTaskForCurrentUser(taskId);
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Task task = taskRepository.findById(taskId)
				.orElseThrow(() -> new IllegalArgumentException("Заявка не найдена: " + taskId));
		if (task.getSla() == null || task.getSla().getId() == null) {
			throw new IllegalStateException("У заявки нет SLA");
		}
		if (Boolean.TRUE.equals(task.getFrozen())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка заморожена");
		}
		if (Boolean.TRUE.equals(task.getCompleted())) {
			throw new IllegalStateException("Нельзя вручную снять SLA с паузы: заявка закрыта");
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndEndedAtIsNull(task.getSla().getId());
		if (activePauses.isEmpty()) {
			return task;
		}
		boolean hasAutoNonWorkingTimePause = activePauses.stream()
				.anyMatch(pause -> Objects.equals(
						pause.getReason(),
						AUTO_NON_WORKING_TIME_SLA_PAUSE_REASON
				));
		if (hasAutoNonWorkingTimePause && shouldBlockManualResumeBecauseOfAutoNonWorkingTime()) {
			throw new IllegalStateException("Нельзя вручную снять SLA с авто-паузы: сейчас нерабочее время");
		}
		String reason = activePauses.getFirst().getReason();
		ZonedDateTime now = ZonedDateTime.now();
		activePauses.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(activePauses);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
		publishManualSlaPauseHistory(client, savedTask, false, reason);
		return savedTask;
	}


	private String normalizeStatusChangeReason(String reason) {
		if (reason == null) {
			return null;
		}
		String normalized = reason.trim();
		return normalized.isBlank() ? null : normalized;
	}


	private void closeAllSlaPausesExceptReason(Task task, String reasonToKeep) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		List<SlaPause> activePauses = slaPauseRepository
				.findAllBySlaIdAndEndedAtIsNull(task.getSla().getId());
		List<SlaPause> pausesToClose = activePauses.stream()
				.filter(pause -> !Objects.equals(pause.getReason(), reasonToKeep))
				.toList();
		if (pausesToClose.isEmpty()) {
			return;
		}
		ZonedDateTime now = ZonedDateTime.now();
		pausesToClose.forEach(pause -> pause.setEndedAt(now));
		slaPauseRepository.saveAll(pausesToClose);
	}


	@Transactional
	public TaskType taskTypeSetDefaultSelection(TaskType selectedTaskType) {
		if (selectedTaskType == null || selectedTaskType.getId() == null) {
			throw new IllegalArgumentException("taskType.id must not be null");
		}
		Long selectedId = selectedTaskType.getId();
		TaskType existingTaskType = taskTypeRepository.findById(selectedId)
				.orElseThrow(() -> new IllegalArgumentException("Тип заявки не найден: %d".formatted(selectedId)));
		List<TaskType> taskTypes = taskTypeRepository.findAll().stream()
				.peek(taskType -> taskType.setDefaultSelection(Objects.equals(taskType.getId(), selectedId)))
				.toList();
		taskTypeRepository.saveAll(taskTypes);
		existingTaskType.setDefaultSelection(true);
		return existingTaskType;
	}


	private boolean hasDefaultTaskType() {
		return taskTypeRepository.findAll().stream()
				.anyMatch(taskType -> Boolean.TRUE.equals(taskType.getDefaultSelection()));
	}


	private Client getClientForCurrentUser(Long clientId) {
		if (clientId == null) {
			throw new IllegalArgumentException("clientId must not be null");
		}
		Client client = clientsRepository.findById(clientId).orElseThrow();
		userService.assertCurrentUserCanAccessClient(client);
		return client;
	}


	private Client getClientByTaskForCurrentUser(Long taskId) {
		if (taskId == null) {
			throw new IllegalArgumentException("taskId must not be null");
		}
		Client client = clientsRepository.findByTaskId(taskId).orElseThrow();
		userService.assertCurrentUserCanAccessClient(client);
		return client;
	}


	private Task createTaskForClient(Client client, Task task) {
		prepareTaskBeforeSave(task);
		setSla(client, task);
		if (task.getMessages() != null && !task.getMessages().isEmpty()) {
			messageRepository.saveAll(task.getMessages());
		}
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		if (client.getTasks() == null) {
			client.setTasks(new ArrayList<>());
		}
		client.getTasks().add(savedTask);
		clientsRepository.save(client);
		globalSearchService.indexClient(client);
		globalSearchService.indexTask(client, savedTask);
		eventPublisher.publish(TriggerType.TASK_CREATED, eventPayload(
				"task", savedTask,
				"client", client,
				"changes", buildTaskCreatedChanges(savedTask)
		));
		if (savedTask.getExecutor() != null) {
			userNotificationService.send(new UserNotification(
					UserNotificationEvent.NEW_TASK,
					savedTask.getName(),
					savedTask.getExecutor().getId()
			));
		}
		return savedTask;
	}

}

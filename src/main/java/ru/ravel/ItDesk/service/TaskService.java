package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.time.Duration;
import java.time.ZonedDateTime;
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

	private static final String FREEZE_SLA_PAUSE_REASON = "Заявка заморожена";
	private static final String MANUAL_SLA_PAUSE_REASON = "Ручная пауза SLA";
	private final UserNotificationService userNotificationService;


	@Transactional(readOnly = true)
	public List<TaskType> getTaskTypes() {
		return taskTypeRepository.findAll();
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
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
				"client", client
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
		Client client = clientsRepository.findById(clientId).orElseThrow();
		FrozenStatus frozenStatus = FrozenStatus.getInstance();
		CompletedStatus completedStatus = CompletedStatus.getInstance();
		Priority oldPriority = olderTask.getPriority();
		Status oldStatus = olderTask.getStatus();
		Status statusBeforeUpdate = olderTask.getStatus();
		boolean oldFrozen = Boolean.TRUE.equals(olderTask.getFrozen());
		User oldExecutor = olderTask.getExecutor();
		Boolean oldCompleted = olderTask.getCompleted();
		ZonedDateTime oldDeadline = olderTask.getDeadline();
		Set<Tag> oldTags = new HashSet<>(Objects.requireNonNullElse(olderTask.getTags(), Collections.emptyList()));
		List<Map<String, Object>> changes = new ArrayList<>();
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
		if (task.getPreviousStatus() != null && !Objects.equals(task.getPreviousStatus(), completedStatus) && !Objects.equals(task.getPreviousStatus(), frozenStatus)) {
			olderTask.setPreviousStatus(task.getPreviousStatus());
		}
		if (Boolean.TRUE.equals(olderTask.getFrozen())) {
			if (!Objects.equals(statusBeforeUpdate, frozenStatus) && !Objects.equals(statusBeforeUpdate, completedStatus)) {
				olderTask.setPreviousStatus(statusBeforeUpdate);
			} else if (!Objects.equals(olderTask.getStatus(), frozenStatus)
					&& !Objects.equals(olderTask.getStatus(), completedStatus)) {
				olderTask.setPreviousStatus(olderTask.getStatus());
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
			addChange(
					changes,
					"slaPause",
					"SLA-пауза",
					oldFrozen ? "Поставлена на паузу" : "Снята с паузы",
					newFrozen ? "Поставлена на паузу" : "Снята с паузы"
			);
		}
		syncSlaPauseByFrozenState(olderTask);
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
		if (!Objects.equals(oldStatus, savedTask.getStatus())) {
			eventPublisher.publish(TriggerType.TASK_STATUS_CHANGED, eventPayload(
					"task", savedTask,
					"client", client,
					"oldStatus", oldStatus,
					"newStatus", savedTask.getStatus()
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
			eventPublisher.publish(TriggerType.TASK_COMPLETED, eventPayload(
					"task", savedTask,
					"client", client
			));
			eventPublisher.publish(TriggerType.TASK_CLOSED, eventPayload(
					"task", savedTask,
					"client", client
			));
		}
		if (Boolean.TRUE.equals(oldCompleted) && !Boolean.TRUE.equals(savedTask.getCompleted())) {
			eventPublisher.publish(TriggerType.TASK_REOPENED, eventPayload(
					"task", savedTask,
					"client", client
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
		if (task == null) {
			return;
		}
		Map<Organization, Map<Priority, SlaValue>> slaByPriority = organizationService.getSlaByPriority();
		Organization organization = client.getOrganization();
		SlaValue slaValue = null;
		if (organization != null) {
			Map<Priority, SlaValue> organizationSla = slaByPriority.entrySet().stream()
					.filter(entry -> Objects.equals(entry.getKey().getId(), organization.getId()))
					.map(Map.Entry::getValue)
					.findFirst()
					.orElse(null);
			if (organizationSla != null && task.getPriority() != null) {
				slaValue = organizationSla.entrySet().stream()
						.filter(entry -> Objects.equals(entry.getKey().getId(), task.getPriority().getId()))
						.map(Map.Entry::getValue)
						.findFirst()
						.orElse(null);
			}
		}
		if (slaValue == null) {
			Map<Priority, SlaValue> defaultSla = slaByPriority.entrySet().stream()
					.filter(entry -> entry.getKey() instanceof DefaultOrganization)
					.map(Map.Entry::getValue)
					.findFirst()
					.orElse(null);
			if (defaultSla != null && task.getPriority() != null) {
				slaValue = defaultSla.entrySet().stream()
						.filter(entry -> Objects.equals(entry.getKey().getId(), task.getPriority().getId()))
						.map(Map.Entry::getValue)
						.findFirst()
						.orElse(null);
			}
		}
		Duration duration = slaValue == null ? Duration.ZERO : slaValue.toDuration();
		Sla sla = Sla.builder()
				.startDate(Objects.requireNonNullElse(task.getCreatedAt(), ZonedDateTime.now()))
				.duration(duration)
				.build();
		slaRepository.save(sla);
		task.setSla(sla);
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


	private static String getName(Object object) {
		return switch (object) {
			case null -> "";
			case Status status -> status.getName();
			case Priority priority -> priority.getName();
			case Tag tag -> tag.getName();
			default -> Objects.toString(object, "");
		};
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
		return taskTypeRepository.save(TaskType.builder()
				.type(type)
				.checklistTemplate(normalizeChecklist(taskType.getChecklistTemplate()))
				.autoApplyChecklist(!Boolean.FALSE.equals(taskType.getAutoApplyChecklist()))
				.build());
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
		return taskTypeRepository.save(taskType);
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
		taskTypeRepository.delete(taskType);
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
		return taskTypeRepository.findByType("Запрос")
				.orElseGet(() -> taskTypeRepository.save(TaskType.builder()
						.type("Запрос")
						.build()));
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


	private void syncSlaPauseByFrozenState(Task task) {
		if (task == null || task.getSla() == null || task.getSla().getId() == null) {
			return;
		}
		boolean nowFrozen = Boolean.TRUE.equals(task.getFrozen());
		if (nowFrozen) {
			openSlaPause(task);
			return;
		}
		closeSlaPause(task);
	}


	private void openSlaPause(Task task) {
		slaPauseRepository
				.findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(
						task.getSla().getId(),
						FREEZE_SLA_PAUSE_REASON
				)
				.ifPresentOrElse(
						pause -> {
							// Уже есть активная пауза заморозки.
						},
						() -> {
							ZonedDateTime startDate = task.getFrozenFrom() != null
									? task.getFrozenFrom()
									: ZonedDateTime.now();
							SlaPause pause = SlaPause.builder()
									.sla(task.getSla())
									.startedAt(startDate)
									.endedAt(null)
									.reason(FREEZE_SLA_PAUSE_REASON)
									.build();
							slaPauseRepository.save(pause);
						}
				);
	}


	private void closeSlaPause(Task task) {
		slaPauseRepository
				.findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(
						task.getSla().getId(),
						FREEZE_SLA_PAUSE_REASON
				)
				.ifPresent(pause -> {
					pause.setEndedAt(ZonedDateTime.now());
					slaPauseRepository.save(pause);
				});
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
		syncSlaPauseByFrozenState(task);
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
		if (!Objects.equals(oldStatus, savedTask.getStatus())) {
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
		SlaPause activePause = slaPauseRepository
				.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId())
				.orElse(null);
		if (activePause == null) {
			return task;
		}
		String reason = activePause.getReason();
		activePause.setEndedAt(ZonedDateTime.now());
		slaPauseRepository.save(activePause);
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
		SlaPause activePause = slaPauseRepository
				.findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(task.getSla().getId())
				.orElse(null);
		if (activePause == null) {
			return task;
		}
		String reason = activePause.getReason();
		activePause.setEndedAt(ZonedDateTime.now());
		slaPauseRepository.save(activePause);
		task.setLastActivity(ZonedDateTime.now());
		Task savedTask = taskRepository.save(task);
		Client client = clientsRepository.findByTaskId(savedTask.getId()).orElse(null);
		publishManualSlaPauseHistory(client, savedTask, false, reason);
		return savedTask;
	}





}

package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class AnalyticsService {

	private static final long DEFAULT_DEADLINE_WARNING_MINUTES = 60L;
	private static final Long EMPTY_GROUP_ID = -1L;

	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;
	private final AutomationOutboxRepository automationOutboxRepository;
	private final SlaService slaService;


	@Transactional(readOnly = true)
	public Map<String, Object> getSummary(
			String from,
			String to,
			String groupBy,
			String typeIds,
			String priorityIds,
			String executorIds,
			String tagIds
	) {
		ZonedDateTime now = ZonedDateTime.now();
		ZonedDateTime safeTo = Objects.requireNonNullElse(parseZonedDateTime(to), now);
		ZonedDateTime safeFrom = Objects.requireNonNullElse(parseZonedDateTime(from), safeTo.minusDays(7));
		String safeGroupBy = Objects.toString(groupBy, "DAY").toUpperCase(Locale.ROOT);
		AnalyticsFilters filters = new AnalyticsFilters(
				parseIds(typeIds),
				parseIds(priorityIds),
				parseIds(executorIds),
				parseIds(tagIds)
		);

		List<Client> clients = clientRepository.findAll();
		List<Task> allTasks = taskRepository.findAll();
		Map<Long, Task> tasksById = allTasks.stream()
				.filter(task -> task.getId() != null)
				.collect(Collectors.toMap(Task::getId, task -> task, (left, right) -> left, LinkedHashMap::new));
		List<Task> tasks = allTasks.stream()
				.filter(task -> matchesFilters(task, filters))
				.toList();
		List<Task> openTasks = tasks.stream()
				.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
				.toList();
		List<Task> closedTasksInPeriod = tasks.stream()
				.filter(task -> Boolean.TRUE.equals(task.getCompleted()))
				.filter(task -> isBetween(task.getClosedAt(), safeFrom, safeTo))
				.toList();
		List<Message> clientMessages = getMessagesForAnalytics(clients, tasks, filters);
		List<AnalyticsEvent> reopenedEvents = getAutomationEvents(TriggerType.TASK_REOPENED, safeFrom, safeTo, tasksById).stream()
				.filter(event -> event.task() != null ? matchesFilters(event.task(), filters) : !filters.hasAny())
				.toList();

		Map<String, Long> closedByPeriodMap = new LinkedHashMap<>();
		Map<String, Long> reopenedByPeriodMap = new LinkedHashMap<>();
		Map<Integer, Map<String, Object>> hourlyLoadMap = createHourlyLoadMap();
		Map<Long, Map<String, Object>> operatorLoadMap = new LinkedHashMap<>();
		Map<String, Map<String, Object>> taskTypeBreakdownMap = new LinkedHashMap<>();
		Map<String, Map<String, Object>> priorityBreakdownMap = new LinkedHashMap<>();
		Map<String, Map<String, Object>> executorBreakdownMap = new LinkedHashMap<>();
		Map<String, Map<String, Object>> tagBreakdownMap = new LinkedHashMap<>();
		List<Long> closeTimeSeconds = new ArrayList<>();

		long newAppeals = 0L;
		for (Message message : clientMessages) {
			if (!isBetween(message.getDate(), safeFrom, safeTo)) {
				continue;
			}
			if (isIncomingMessage(message)) {
				newAppeals++;
				incrementHourlyLoad(hourlyLoadMap, message.getDate(), "incomingMessages");
			} else if (isOutgoingOperatorMessage(message)) {
				incrementHourlyLoad(hourlyLoadMap, message.getDate(), "outgoingMessages");
			}
		}

		for (Task task : tasks) {
			incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "totalTasks");

			if (isBetween(task.getCreatedAt(), safeFrom, safeTo)) {
				incrementHourlyLoad(hourlyLoadMap, task.getCreatedAt(), "createdTasks");
				incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "createdTasks");
			}
		}

		long overdueSla = openTasks.stream()
				.filter(task -> isTaskSlaOverdue(task, now))
				.count();
		long overdueDeadlines = openTasks.stream()
				.filter(task -> isTaskDeadlineOverdue(task, now))
				.count();
		long deadlineWarnings = openTasks.stream()
				.filter(task -> isTaskDeadlineWarning(task, now, DEFAULT_DEADLINE_WARNING_MINUTES))
				.count();
		long unassignedTasks = openTasks.stream()
				.filter(task -> task.getExecutor() == null)
				.count();
		long unansweredMessages = filters.hasAny()
				? countUnansweredMessages(clientMessages)
				: countUnansweredMessagesByClient(clients);
		long avgFirstResponseSeconds = averageSeconds(getFirstResponseSeconds(clients, safeFrom, safeTo));

		for (Task task : openTasks) {
			User executor = task.getExecutor();
			if (executor != null) {
				incrementOperatorLoad(operatorLoadMap, executor, "openTasks");
			}
			incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "openTasks");
			if (isTaskSlaOverdue(task, now)) {
				if (executor != null) {
					incrementOperatorLoad(operatorLoadMap, executor, "overdueSla");
				}
				incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "overdueSla");
			}
			if (isTaskDeadlineOverdue(task, now)) {
				if (executor != null) {
					incrementOperatorLoad(operatorLoadMap, executor, "overdueDeadlines");
				}
				incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "overdueDeadlines");
			}
			if (executor == null) {
				incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "unassignedTasks");
			}
		}

		for (Task task : closedTasksInPeriod) {
			ZonedDateTime closedAt = task.getClosedAt();
			if (closedAt != null) {
				closedByPeriodMap.merge(getPeriodLabel(closedAt, safeGroupBy), 1L, Long::sum);
				incrementHourlyLoad(hourlyLoadMap, closedAt, "closedTasks");
			}
			if (task.getExecutor() != null) {
				incrementOperatorLoad(operatorLoadMap, task.getExecutor(), "closedTasks");
			}
			incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "closedTasks");
			if (task.getCreatedAt() != null && closedAt != null && !closedAt.isBefore(task.getCreatedAt())) {
				closeTimeSeconds.add(Duration.between(task.getCreatedAt(), closedAt).getSeconds());
			}
		}

		for (AnalyticsEvent reopenedEvent : reopenedEvents) {
			ZonedDateTime eventDate = reopenedEvent.date();
			reopenedByPeriodMap.merge(getPeriodLabel(eventDate, safeGroupBy), 1L, Long::sum);
			incrementHourlyLoad(hourlyLoadMap, eventDate, "reopenedTasks");

			Task task = reopenedEvent.task();
			if (task == null) {
				continue;
			}
			if (task.getExecutor() != null) {
				incrementOperatorLoad(operatorLoadMap, task.getExecutor(), "reopenedTasks");
			}
			incrementBreakdowns(taskTypeBreakdownMap, priorityBreakdownMap, executorBreakdownMap, tagBreakdownMap, task, "reopenedTasks");
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("from", safeFrom);
		result.put("to", safeTo);
		result.put("groupBy", safeGroupBy);
		result.put("filters", filters.toMap());
		result.put("newAppeals", newAppeals);
		result.put("openTasks", (long) openTasks.size());
		result.put("overdueSla", overdueSla);
		result.put("overdueDeadlines", overdueDeadlines);
		result.put("deadlineWarnings", deadlineWarnings);
		result.put("deadlineWarningMinutes", DEFAULT_DEADLINE_WARNING_MINUTES);
		result.put("unansweredMessages", unansweredMessages);
		result.put("avgFirstResponseSeconds", avgFirstResponseSeconds);
		result.put("avgCloseTimeSeconds", averageSeconds(closeTimeSeconds));
		result.put("unassignedTasks", unassignedTasks);
		result.put("closedTasks", (long) closedTasksInPeriod.size());
		result.put("reopenedTasks", (long) reopenedEvents.size());
		result.put("closedByPeriod", toPeriodRows(closedByPeriodMap));
		result.put("reopenedByPeriod", toPeriodRows(reopenedByPeriodMap));
		result.put("hourlyLoad", toHourlyRows(hourlyLoadMap));
		result.put("operatorLoad", toOperatorRows(operatorLoadMap));
		result.put("taskTypeBreakdown", toBreakdownRows(taskTypeBreakdownMap));
		result.put("priorityBreakdown", toBreakdownRows(priorityBreakdownMap));
		result.put("executorBreakdown", toBreakdownRows(executorBreakdownMap));
		result.put("tagBreakdown", toBreakdownRows(tagBreakdownMap));
		return result;
	}


	private List<Message> getMessagesForAnalytics(List<Client> clients, List<Task> tasks, AnalyticsFilters filters) {
		if (!filters.hasAny()) {
			return clients.stream()
					.flatMap(client -> safeCollection(client.getMessages()).stream())
					.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
					.toList();
		}

		Map<Long, Message> result = new LinkedHashMap<>();
		Map<Long, Message> clientMessagesById = clients.stream()
				.flatMap(client -> safeCollection(client.getMessages()).stream())
				.filter(message -> message.getId() != null)
				.collect(Collectors.toMap(Message::getId, message -> message, (left, right) -> left, LinkedHashMap::new));

		for (Task task : tasks) {
			for (Message message : safeCollection(task.getMessages())) {
				if (message != null && message.getId() != null && !Boolean.TRUE.equals(message.getDeleted())) {
					result.put(message.getId(), message);
				}
			}
			if (task.getLinkedMessageId() != null) {
				Message linkedMessage = clientMessagesById.get(task.getLinkedMessageId());
				if (linkedMessage != null && !Boolean.TRUE.equals(linkedMessage.getDeleted())) {
					result.put(linkedMessage.getId(), linkedMessage);
				}
			}
		}

		return new ArrayList<>(result.values());
	}


	private List<Long> getFirstResponseSeconds(List<Client> clients, ZonedDateTime from, ZonedDateTime to) {
		List<Long> result = new ArrayList<>();

		for (Client client : clients) {
			ZonedDateTime firstPendingIncomingMessageDate = null;

			List<Message> sortedMessages = safeCollection(client.getMessages()).stream()
					.filter(message -> message.getDate() != null)
					.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
					.sorted()
					.toList();

			for (Message message : sortedMessages) {
				if (isIncomingMessage(message)) {
					if (firstPendingIncomingMessageDate == null && isBetween(message.getDate(), from, to)) {
						firstPendingIncomingMessageDate = message.getDate();
					}
					continue;
				}

				if (isOutgoingOperatorMessage(message) && firstPendingIncomingMessageDate != null && message.getDate().isAfter(firstPendingIncomingMessageDate)) {
					result.add(Duration.between(firstPendingIncomingMessageDate, message.getDate()).getSeconds());
					firstPendingIncomingMessageDate = null;
				}
			}
		}

		return result;
	}


	private long countUnansweredMessagesByClient(List<Client> clients) {
		long count = 0L;

		for (Client client : clients) {
			count += countUnansweredMessages(new ArrayList<>(safeCollection(client.getMessages())));
		}

		return count;
	}


	private long countUnansweredMessages(List<Message> messages) {
		long pendingIncoming = 0L;
		List<Message> sortedMessages = safeCollection(messages).stream()
				.filter(message -> message.getDate() != null)
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
				.sorted()
				.toList();

		for (Message message : sortedMessages) {
			if (isIncomingMessage(message)) {
				pendingIncoming++;
			} else if (isOutgoingOperatorMessage(message)) {
				pendingIncoming = 0L;
			}
		}

		return pendingIncoming;
	}


	private boolean isIncomingMessage(Message message) {
		return message != null
				&& Boolean.FALSE.equals(message.getIsSent())
				&& !Boolean.TRUE.equals(message.getIsComment())
				&& !Boolean.TRUE.equals(message.getDeleted());
	}


	private boolean isOutgoingOperatorMessage(Message message) {
		return message != null
				&& Boolean.TRUE.equals(message.getIsSent())
				&& !Boolean.TRUE.equals(message.getIsComment())
				&& !Boolean.TRUE.equals(message.getDeleted());
	}


	private boolean isTaskSlaOverdue(Task task, ZonedDateTime now) {
		if (task == null || task.getSla() == null || Boolean.TRUE.equals(task.getCompleted())) {
			return false;
		}

		ZonedDateTime deadline = slaService.deadline(task.getSla());
		return deadline != null && deadline.isBefore(now);
	}


	private boolean isTaskDeadlineOverdue(Task task, ZonedDateTime now) {
		return task != null
				&& task.getDeadline() != null
				&& !Boolean.TRUE.equals(task.getCompleted())
				&& task.getDeadline().isBefore(now);
	}


	private boolean isTaskDeadlineWarning(Task task, ZonedDateTime now, long warningMinutes) {
		if (task == null || task.getDeadline() == null || Boolean.TRUE.equals(task.getCompleted())) {
			return false;
		}
		if (task.getDeadline().isBefore(now)) {
			return false;
		}
		return !task.getDeadline().isAfter(now.plusMinutes(warningMinutes));
	}


	private boolean isBetween(ZonedDateTime date, ZonedDateTime from, ZonedDateTime to) {
		return date != null
				&& !date.isBefore(from)
				&& !date.isAfter(to);
	}


	private List<Map<String, Object>> toPeriodRows(Map<String, Long> map) {
		return map.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(entry -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("period", entry.getKey());
					row.put("count", entry.getValue());
					return row;
				})
				.toList();
	}


	private List<Map<String, Object>> toOperatorRows(Map<Long, Map<String, Object>> operatorLoadMap) {
		return operatorLoadMap.values().stream()
				.sorted(Comparator.comparingLong((Map<String, Object> item) ->
						asLong(item.get("openTasks"))
								+ asLong(item.get("closedTasks"))
								+ asLong(item.get("reopenedTasks"))
								+ asLong(item.get("overdueSla"))
								+ asLong(item.get("overdueDeadlines"))
				).reversed())
				.toList();
	}


	private void incrementOperatorLoad(Map<Long, Map<String, Object>> operatorLoadMap, User user, String metric) {
		if (user == null || user.getId() == null) {
			return;
		}

		Map<String, Object> row = operatorLoadMap.computeIfAbsent(user.getId(), userId -> {
			Map<String, Object> created = new LinkedHashMap<>();
			created.put("userId", user.getId());
			created.put("name", getUserDisplayName(user));
			created.put("openTasks", 0L);
			created.put("closedTasks", 0L);
			created.put("overdueSla", 0L);
			created.put("overdueDeadlines", 0L);
			created.put("reopenedTasks", 0L);
			return created;
		});

		row.put(metric, asLong(row.get(metric)) + 1L);
	}


	private void incrementBreakdowns(
			Map<String, Map<String, Object>> taskTypeBreakdownMap,
			Map<String, Map<String, Object>> priorityBreakdownMap,
			Map<String, Map<String, Object>> executorBreakdownMap,
			Map<String, Map<String, Object>> tagBreakdownMap,
			Task task,
			String metric
	) {
		if (task == null) {
			return;
		}

		Object type = task.getType();
		incrementBreakdown(taskTypeBreakdownMap, getEntityId(type), getEntityName(type, "Без типа"), metric);

		Object priority = task.getPriority();
		incrementBreakdown(priorityBreakdownMap, getEntityId(priority), getEntityName(priority, "Без приоритета"), metric);

		User executor = task.getExecutor();
		incrementBreakdown(executorBreakdownMap, executor == null ? null : executor.getId(), executor == null ? "Без исполнителя" : getUserDisplayName(executor), metric);

		Collection<?> tags = safeCollection(task.getTags());
		if (tags.isEmpty()) {
			incrementBreakdown(tagBreakdownMap, null, "Без тегов", metric);
			return;
		}
		for (Object tag : tags) {
			incrementBreakdown(tagBreakdownMap, getEntityId(tag), getEntityName(tag, "Без тега"), metric);
		}
	}


	private void incrementBreakdown(Map<String, Map<String, Object>> breakdownMap, Long id, String name, String metric) {
		String safeName = Objects.toString(name, "Без значения").isBlank() ? "Без значения" : name;
		Long safeId = id == null ? EMPTY_GROUP_ID : id;
		String key = safeId + ":" + safeName;
		Map<String, Object> row = breakdownMap.computeIfAbsent(key, ignored -> {
			Map<String, Object> created = new LinkedHashMap<>();
			created.put("key", key);
			created.put("id", safeId);
			created.put("name", safeName);
			created.put("totalTasks", 0L);
			created.put("createdTasks", 0L);
			created.put("openTasks", 0L);
			created.put("closedTasks", 0L);
			created.put("reopenedTasks", 0L);
			created.put("overdueSla", 0L);
			created.put("overdueDeadlines", 0L);
			created.put("unassignedTasks", 0L);
			return created;
		});
		row.put(metric, asLong(row.get(metric)) + 1L);
	}


	private List<Map<String, Object>> toBreakdownRows(Map<String, Map<String, Object>> breakdownMap) {
		return breakdownMap.values().stream()
				.sorted(Comparator.comparingLong((Map<String, Object> item) -> asLong(item.get("totalTasks"))).reversed())
				.toList();
	}


	private Map<Integer, Map<String, Object>> createHourlyLoadMap() {
		Map<Integer, Map<String, Object>> result = new LinkedHashMap<>();
		for (int hour = 0; hour < 24; hour++) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("hour", hour);
			row.put("label", "%02d:00".formatted(hour));
			row.put("incomingMessages", 0L);
			row.put("outgoingMessages", 0L);
			row.put("createdTasks", 0L);
			row.put("closedTasks", 0L);
			row.put("reopenedTasks", 0L);
			row.put("total", 0L);
			result.put(hour, row);
		}
		return result;
	}


	private void incrementHourlyLoad(Map<Integer, Map<String, Object>> hourlyLoadMap, ZonedDateTime date, String metric) {
		if (date == null) {
			return;
		}
		Map<String, Object> row = hourlyLoadMap.get(date.getHour());
		if (row == null) {
			return;
		}
		row.put(metric, asLong(row.get(metric)) + 1L);
		row.put("total", asLong(row.get("total")) + 1L);
	}


	private List<Map<String, Object>> toHourlyRows(Map<Integer, Map<String, Object>> hourlyLoadMap) {
		return hourlyLoadMap.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.map(Map.Entry::getValue)
				.toList();
	}


	private boolean matchesFilters(Task task, AnalyticsFilters filters) {
		if (task == null) {
			return false;
		}
		if (!filters.typeIds().isEmpty() && !filters.typeIds().contains(getEntityId(task.getType()))) {
			return false;
		}
		if (!filters.priorityIds().isEmpty() && !filters.priorityIds().contains(getEntityId(task.getPriority()))) {
			return false;
		}
		if (!filters.executorIds().isEmpty()) {
			Long executorId = task.getExecutor() == null ? null : task.getExecutor().getId();
			if (!filters.executorIds().contains(executorId)) {
				return false;
			}
		}
		if (!filters.tagIds().isEmpty()) {
			boolean hasMatchingTag = safeCollection(task.getTags()).stream()
					.map(this::getEntityId)
					.anyMatch(filters.tagIds()::contains);
			if (!hasMatchingTag) {
				return false;
			}
		}
		return true;
	}


	private Set<Long> parseIds(String value) {
		Set<Long> result = new LinkedHashSet<>();
		if (value == null || value.isBlank()) {
			return result;
		}
		String[] parts = value.split(",");
		for (String part : parts) {
			if (part == null || part.isBlank()) {
				continue;
			}
			try {
				result.add(Long.parseLong(part.trim()));
			} catch (NumberFormatException ignored) {
			}
		}
		return result;
	}


	private List<AnalyticsEvent> getAutomationEvents(TriggerType triggerType, ZonedDateTime from, ZonedDateTime to, Map<Long, Task> tasksById) {
		List<AnalyticsEvent> result = new ArrayList<>();
		List<?> events;

		try {
			events = automationOutboxRepository.findAll();
		} catch (Exception ignored) {
			return result;
		}

		for (Object event : events) {
			String eventType = getEventType(event);
			if (!Objects.equals(triggerType.name(), eventType)) {
				continue;
			}
			ZonedDateTime eventDate = getEventDate(event);
			if (!isBetween(eventDate, from, to)) {
				continue;
			}
			Task task = getEventTask(event, tasksById);
			result.add(new AnalyticsEvent(event, eventDate, task));
		}

		return result;
	}


	private Task getEventTask(Object event, Map<Long, Task> tasksById) {
		Task directTask = asTask(firstGetterValue(event, "getTask"), tasksById);
		if (directTask != null) {
			return directTask;
		}

		Object payload = firstGetterValue(event,
				"getPayload",
				"getPayloadJson",
				"getData",
				"getBody",
				"getMessage"
		);
		return asTask(payload, tasksById);
	}


	private Task asTask(Object value, Map<Long, Task> tasksById) {
		if (value == null) {
			return null;
		}
		if (value instanceof Task task) {
			return task;
		}
		if (value instanceof Map<?, ?> map) {
			Object taskValue = map.get("task");
			if (taskValue != null) {
				Task task = asTask(taskValue, tasksById);
				if (task != null) {
					return task;
				}
			}
			Long taskId = asLongOrNull(map.get("taskId"));
			if (taskId == null && taskValue instanceof Map<?, ?> taskMap) {
				taskId = asLongOrNull(taskMap.get("id"));
			}
			return taskId == null ? null : tasksById.get(taskId);
		}
		if (value instanceof CharSequence text) {
			Long taskId = findTaskId(text.toString());
			return taskId == null ? null : tasksById.get(taskId);
		}
		Long id = getEntityId(value);
		return id == null ? null : tasksById.get(id);
	}


	private Long findTaskId(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.replace("\\\"", "\"");
		List<Pattern> patterns = List.of(
				Pattern.compile("\\\"task\\\"\\s*:\\s*\\{[^}]*\\\"id\\\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
				Pattern.compile("\\\"taskId\\\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
				Pattern.compile("taskId\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE)
		);
		for (Pattern pattern : patterns) {
			Matcher matcher = pattern.matcher(normalized);
			if (matcher.find()) {
				return Long.parseLong(matcher.group(1));
			}
		}
		return null;
	}


	private String getEventType(Object event) {
		Object value = firstGetterValue(event,
				"getTriggerType",
				"getType",
				"getEventType",
				"getName"
		);
		if (value == null) {
			return null;
		}
		if (value instanceof Enum<?> enumValue) {
			return enumValue.name();
		}
		return Objects.toString(value, null).replace("\"", "").trim();
	}


	private ZonedDateTime getEventDate(Object event) {
		Object value = firstGetterValue(event,
				"getCreatedAt",
				"getCreatedDate",
				"getOccurredAt",
				"getProcessedAt",
				"getDate",
				"getTimestamp"
		);
		return asZonedDateTime(value);
	}


	private Object firstGetterValue(Object source, String... methodNames) {
		if (source == null) {
			return null;
		}
		for (String methodName : methodNames) {
			Object value = invokeGetter(source, methodName);
			if (value != null) {
				return value;
			}
		}
		return null;
	}


	private Object invokeGetter(Object source, String methodName) {
		try {
			Method method = source.getClass().getMethod(methodName);
			return method.invoke(source);
		} catch (Exception ignored) {
			return null;
		}
	}


	private ZonedDateTime asZonedDateTime(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof ZonedDateTime zonedDateTime) {
			return zonedDateTime;
		}
		if (value instanceof Instant instant) {
			return instant.atZone(ZoneId.systemDefault());
		}
		if (value instanceof LocalDateTime localDateTime) {
			return localDateTime.atZone(ZoneId.systemDefault());
		}
		if (value instanceof Date date) {
			return date.toInstant().atZone(ZoneId.systemDefault());
		}
		if (value instanceof CharSequence text) {
			return parseZonedDateTime(text.toString());
		}
		return null;
	}


	private String getUserDisplayName(User user) {
		String lastname = Objects.toString(user.getLastname(), "").trim();
		String firstname = Objects.toString(user.getFirstname(), "").trim();
		String fullName = (lastname + " " + firstname).trim();

		if (!fullName.isBlank()) {
			return fullName;
		}

		return Objects.toString(user.getUsername(), "Пользователь " + user.getId());
	}


	private Long getEntityId(Object source) {
		Object value = firstGetterValue(source, "getId");
		return asLongOrNull(value);
	}


	private String getEntityName(Object source, String fallback) {
		Object value = firstGetterValue(source, "getName", "getType", "getTitle", "getUsername");
		String name = Objects.toString(value, "").trim();
		return name.isBlank() ? fallback : name;
	}


	private String getPeriodLabel(ZonedDateTime date, String groupBy) {
		if ("WEEK".equalsIgnoreCase(groupBy)) {
			ZonedDateTime startOfWeek = date.minusDays(date.getDayOfWeek().getValue() - 1L).toLocalDate().atStartOfDay(date.getZone());
			return startOfWeek.toLocalDate().toString();
		}
		return date.toLocalDate().toString();
	}


	private long averageSeconds(List<Long> values) {
		if (values == null || values.isEmpty()) {
			return 0L;
		}

		return Math.round(values.stream()
				.mapToLong(Long::longValue)
				.average()
				.orElse(0D));
	}


	private long asLong(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}

		return 0L;
	}


	private Long asLongOrNull(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof CharSequence text) {
			try {
				return Long.parseLong(text.toString().replace("\"", "").trim());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}
		return null;
	}


	private ZonedDateTime parseZonedDateTime(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return ZonedDateTime.parse(value);
		} catch (Exception ignored) {
		}
		try {
			return Instant.parse(value).atZone(ZoneId.systemDefault());
		} catch (Exception ignored) {
			return null;
		}
	}


	private static <T> Collection<T> safeCollection(Collection<T> collection) {
		return collection == null ? List.of() : collection;
	}


	private record AnalyticsEvent(Object source, ZonedDateTime date, Task task) {
	}


	private record AnalyticsFilters(Set<Long> typeIds, Set<Long> priorityIds, Set<Long> executorIds, Set<Long> tagIds) {
		private boolean hasAny() {
			return !typeIds.isEmpty() || !priorityIds.isEmpty() || !executorIds.isEmpty() || !tagIds.isEmpty();
		}

		private Map<String, Object> toMap() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("typeIds", typeIds);
			result.put("priorityIds", priorityIds);
			result.put("executorIds", executorIds);
			result.put("tagIds", tagIds);
			return result;
		}
	}
}

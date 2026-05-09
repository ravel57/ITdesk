package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;


@Service
@RequiredArgsConstructor
public class AnalyticsService {

	private final ClientRepository clientRepository;
	private final TaskRepository taskRepository;
	private final AutomationOutboxRepository automationOutboxRepository;
	private final SlaService slaService;


	@Transactional(readOnly = true)
	public Map<String, Object> getSummary(String from, String to, String groupBy) {
		ZonedDateTime now = ZonedDateTime.now();
		ZonedDateTime safeTo = Objects.requireNonNullElse(parseZonedDateTime(to), now);
		ZonedDateTime safeFrom = Objects.requireNonNullElse(parseZonedDateTime(from), safeTo.minusDays(7));
		String safeGroupBy = Objects.toString(groupBy, "DAY").toUpperCase(Locale.ROOT);
		List<Client> clients = clientRepository.findAll();
		List<Task> tasks = taskRepository.findAll();
		List<Task> openTasks = tasks.stream()
				.filter(task -> !Boolean.TRUE.equals(task.getCompleted()))
				.toList();
		List<Task> closedTasksInPeriod = tasks.stream()
				.filter(task -> Boolean.TRUE.equals(task.getCompleted()))
				.filter(task -> isBetween(task.getClosedAt(), safeFrom, safeTo))
				.toList();
		List<Message> clientMessages = clients.stream()
				.flatMap(client -> safeCollection(client.getMessages()).stream())
				.filter(message -> !Boolean.TRUE.equals(message.getDeleted()))
				.toList();
		long newAppeals = clientMessages.stream()
				.filter(this::isIncomingMessage)
				.filter(message -> isBetween(message.getDate(), safeFrom, safeTo))
				.count();
		long overdueSla = openTasks.stream()
				.filter(task -> isTaskSlaOverdue(task, now))
				.count();
		long unassignedTasks = openTasks.stream()
				.filter(task -> task.getExecutor() == null)
				.count();
		long avgFirstResponseSeconds = averageSeconds(getFirstResponseSeconds(clients, safeFrom, safeTo));
		Map<String, Long> closedByPeriodMap = new LinkedHashMap<>();
		Map<Long, Map<String, Object>> operatorLoadMap = new LinkedHashMap<>();
		List<Long> closeTimeSeconds = new ArrayList<>();
		for (Task task : openTasks) {
			User executor = task.getExecutor();
			if (executor == null) {
				continue;
			}
			incrementOperatorLoad(operatorLoadMap, executor, "openTasks");
			if (isTaskSlaOverdue(task, now)) {
				incrementOperatorLoad(operatorLoadMap, executor, "overdueSla");
			}
		}
		for (Task task : closedTasksInPeriod) {
			ZonedDateTime closedAt = task.getClosedAt();
			if (closedAt != null) {
				closedByPeriodMap.merge(getPeriodLabel(closedAt, safeGroupBy), 1L, Long::sum);
			}
			if (task.getExecutor() != null) {
				incrementOperatorLoad(operatorLoadMap, task.getExecutor(), "closedTasks");
			}
			if (task.getCreatedAt() != null && closedAt != null && !closedAt.isBefore(task.getCreatedAt())) {
				closeTimeSeconds.add(Duration.between(task.getCreatedAt(), closedAt).getSeconds());
			}
		}
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("from", safeFrom);
		result.put("to", safeTo);
		result.put("groupBy", safeGroupBy);
		result.put("newAppeals", newAppeals);
		result.put("openTasks", (long) openTasks.size());
		result.put("overdueSla", overdueSla);
		result.put("avgFirstResponseSeconds", avgFirstResponseSeconds);
		result.put("avgCloseTimeSeconds", averageSeconds(closeTimeSeconds));
		result.put("unassignedTasks", unassignedTasks);
		result.put("closedTasks", (long) closedTasksInPeriod.size());
		result.put("closedByPeriod", toPeriodRows(closedByPeriodMap));
		result.put("operatorLoad", toOperatorRows(operatorLoadMap));
		return result;
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


	private boolean isBetween(ZonedDateTime date, ZonedDateTime from, ZonedDateTime to) {
		return date != null
				&& !date.isBefore(from)
				&& !date.isAfter(to);
	}


	private List<Map<String, Object>> toPeriodRows(Map<String, Long> map) {
		return map.entrySet().stream()
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
						asLong(item.get("openTasks")) + asLong(item.get("closedTasks")) + asLong(item.get("overdueSla"))
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
			return created;
		});

		row.put(metric, asLong(row.get(metric)) + 1L);
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
}
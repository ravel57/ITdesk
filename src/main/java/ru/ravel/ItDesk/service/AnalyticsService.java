package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.lang.reflect.Method;
import java.time.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@Service
@RequiredArgsConstructor
public class AnalyticsService {

	private static final long DEFAULT_DEADLINE_WARNING_MINUTES = 60L;
	private static final Long EMPTY_GROUP_ID = -1L;

	private final TaskRepository taskRepository;
	private final MessageRepository messageRepository;
	private final AutomationOutboxRepository automationOutboxRepository;
	private final SlaService slaService;
	private final AppSettingsService appSettingsService;
	private final ConcurrentMap<String, AnalyticsCancellationToken> activeAnalyticsRequests = new ConcurrentHashMap<>();


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
		return getSummary(null, from, to, groupBy, typeIds, priorityIds, executorIds, tagIds);
	}


	@Transactional(readOnly = true)
	public Map<String, Object> getSummary(
			String requestKey,
			String from,
			String to,
			String groupBy,
			String typeIds,
			String priorityIds,
			String executorIds,
			String tagIds
	) {
		AnalyticsCancellationToken cancellationToken = registerAnalyticsRequest(requestKey);
		try {
			checkAnalyticsCancelled(cancellationToken);
			AnalyticsWorkingTime workingTime = getAnalyticsWorkingTime();
			ZoneId analyticsZone = workingTime.zone();
			ZonedDateTime now = ZonedDateTime.now(analyticsZone);
			ZonedDateTime safeTo = Objects.requireNonNullElse(parseZonedDateTime(to, analyticsZone), now);
			ZonedDateTime safeFrom = Objects.requireNonNullElse(parseZonedDateTime(from, analyticsZone), safeTo.minusDays(7));
			String safeGroupBy = Objects.toString(groupBy, "DAY").toUpperCase(Locale.ROOT);
			AnalyticsFilters filters = new AnalyticsFilters(
					parseIds(typeIds),
					parseIds(priorityIds),
					parseIds(executorIds),
					parseIds(tagIds)
			);

			List<TaskRepository.AnalyticsTaskRow> taskRows = taskRepository.findAnalyticsTaskRows(
					!filters.typeIds().isEmpty(),
					idsOrDummy(filters.typeIds()),
					!filters.priorityIds().isEmpty(),
					idsOrDummy(filters.priorityIds()),
					!filters.executorIds().isEmpty(),
					idsOrDummy(filters.executorIds()),
					!filters.tagIds().isEmpty(),
					idsOrDummy(filters.tagIds())
			);
			checkAnalyticsCancelled(cancellationToken);

			Map<Long, List<Object>> tagsByTaskId = getTagsByTaskId(filters, cancellationToken);
			Map<Long, TaskRepository.AnalyticsTaskRow> taskRowsById = new LinkedHashMap<>();

			Map<String, Long> closedByPeriodMap = new LinkedHashMap<>();
			Map<String, Long> reopenedByPeriodMap = new LinkedHashMap<>();
			Map<Integer, Map<String, Object>> hourlyLoadMap = createHourlyLoadMap();
			Map<Long, Map<String, Object>> operatorLoadMap = new LinkedHashMap<>();
			Map<String, Map<String, Object>> taskTypeBreakdownMap = new LinkedHashMap<>();
			Map<String, Map<String, Object>> priorityBreakdownMap = new LinkedHashMap<>();
			Map<String, Map<String, Object>> executorBreakdownMap = new LinkedHashMap<>();
			Map<String, Map<String, Object>> tagBreakdownMap = new LinkedHashMap<>();
			List<Long> closeTimeSeconds = new ArrayList<>();

			long openTasks = 0L;
			long closedTasks = 0L;
			long overdueDeadlines = 0L;
			long deadlineWarnings = 0L;
			long unassignedTasks = 0L;

			for (TaskRepository.AnalyticsTaskRow row : taskRows) {
				checkAnalyticsCancelled(cancellationToken);

				if (row.getId() != null) {
					taskRowsById.put(row.getId(), row);
				}

				Collection<?> tags = tagsByTaskId.getOrDefault(row.getId(), List.of());

				incrementBreakdowns(
						taskTypeBreakdownMap,
						priorityBreakdownMap,
						executorBreakdownMap,
						tagBreakdownMap,
						row.getType(),
						row.getPriority(),
						row.getExecutor(),
						tags,
						"totalTasks"
				);

				if (isBetween(row.getCreatedAt(), safeFrom, safeTo)) {
					incrementHourlyLoad(hourlyLoadMap, row.getCreatedAt(), analyticsZone, "createdTasks");
					incrementBreakdowns(
							taskTypeBreakdownMap,
							priorityBreakdownMap,
							executorBreakdownMap,
							tagBreakdownMap,
							row.getType(),
							row.getPriority(),
							row.getExecutor(),
							tags,
							"createdTasks"
					);
				}

				if (!Boolean.TRUE.equals(row.getCompleted())) {
					openTasks++;

					if (row.getExecutor() != null) {
						incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "openTasks");
					}

					incrementBreakdowns(
							taskTypeBreakdownMap,
							priorityBreakdownMap,
							executorBreakdownMap,
							tagBreakdownMap,
							row.getType(),
							row.getPriority(),
							row.getExecutor(),
							tags,
							"openTasks"
					);

					if (isTaskDeadlineOverdue(row.getDeadline(), now)) {
						overdueDeadlines++;

						if (row.getExecutor() != null) {
							incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "overdueDeadlines");
						}

						incrementBreakdowns(
								taskTypeBreakdownMap,
								priorityBreakdownMap,
								executorBreakdownMap,
								tagBreakdownMap,
								row.getType(),
								row.getPriority(),
								row.getExecutor(),
								tags,
								"overdueDeadlines"
						);
					}

					if (isTaskDeadlineWarning(row.getDeadline(), now, DEFAULT_DEADLINE_WARNING_MINUTES)) {
						deadlineWarnings++;
					}

					if (row.getExecutor() == null) {
						unassignedTasks++;
						incrementBreakdowns(
								taskTypeBreakdownMap,
								priorityBreakdownMap,
								executorBreakdownMap,
								tagBreakdownMap,
								row.getType(),
								row.getPriority(),
								row.getExecutor(),
								tags,
								"unassignedTasks"
						);
					}

					continue;
				}

				if (isBetween(row.getClosedAt(), safeFrom, safeTo)) {
					closedTasks++;

					closedByPeriodMap.merge(getPeriodLabel(row.getClosedAt(), safeGroupBy, analyticsZone), 1L, Long::sum);
					incrementHourlyLoad(hourlyLoadMap, row.getClosedAt(), analyticsZone, "closedTasks");

					if (row.getExecutor() != null) {
						incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "closedTasks");
					}

					incrementBreakdowns(
							taskTypeBreakdownMap,
							priorityBreakdownMap,
							executorBreakdownMap,
							tagBreakdownMap,
							row.getType(),
							row.getPriority(),
							row.getExecutor(),
							tags,
							"closedTasks"
					);

					if (row.getCreatedAt() != null && row.getClosedAt() != null && !row.getClosedAt().isBefore(row.getCreatedAt())) {
						closeTimeSeconds.add(getWorkingSeconds(row.getCreatedAt(), row.getClosedAt(), workingTime, cancellationToken));
					}
				}
			}

			long overdueSla = countOverdueSla(
					now,
					filters,
					tagsByTaskId,
					operatorLoadMap,
					taskTypeBreakdownMap,
					priorityBreakdownMap,
					executorBreakdownMap,
					tagBreakdownMap,
					cancellationToken
			);

			List<AnalyticsMessageRow> clientMessages = getClientMessageRows(safeFrom, safeTo, cancellationToken);

			long newAppeals = 0L;
			for (AnalyticsMessageRow message : clientMessages) {
				checkAnalyticsCancelled(cancellationToken);

				if (isIncomingMessageRequiringAnswer(message)) {
					newAppeals++;
					incrementHourlyLoad(hourlyLoadMap, message.date(), analyticsZone, "incomingMessages");
				} else if (isOutgoingOperatorMessage(message)) {
					incrementHourlyLoad(hourlyLoadMap, message.date(), analyticsZone, "outgoingMessages");
				}
			}

			long unansweredMessages = countUnansweredMessages(clientMessages, cancellationToken);
			long avgFirstResponseSeconds = averageSeconds(getFirstResponseSeconds(clientMessages, workingTime, cancellationToken));

			List<AnalyticsEvent> reopenedEvents = getAutomationEvents(
					TriggerType.TASK_REOPENED,
					safeFrom,
					safeTo,
					analyticsZone,
					cancellationToken
			);

			long reopenedTasks = 0L;
			for (AnalyticsEvent reopenedEvent : reopenedEvents) {
				checkAnalyticsCancelled(cancellationToken);

				TaskRepository.AnalyticsTaskRow taskRow = reopenedEvent.taskId() == null ? null : taskRowsById.get(reopenedEvent.taskId());

				if (filters.hasAny() && taskRow == null) {
					continue;
				}

				reopenedTasks++;
				ZonedDateTime eventDate = reopenedEvent.date();
				reopenedByPeriodMap.merge(getPeriodLabel(eventDate, safeGroupBy, analyticsZone), 1L, Long::sum);
				incrementHourlyLoad(hourlyLoadMap, eventDate, analyticsZone, "reopenedTasks");

				if (taskRow == null) {
					continue;
				}

				if (taskRow.getExecutor() != null) {
					incrementOperatorLoad(operatorLoadMap, taskRow.getExecutor(), "reopenedTasks");
				}

				incrementBreakdowns(
						taskTypeBreakdownMap,
						priorityBreakdownMap,
						executorBreakdownMap,
						tagBreakdownMap,
						taskRow.getType(),
						taskRow.getPriority(),
						taskRow.getExecutor(),
						tagsByTaskId.getOrDefault(taskRow.getId(), List.of()),
						"reopenedTasks"
				);
			}
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("from", safeFrom);
			result.put("to", safeTo);
			result.put("groupBy", safeGroupBy);
			result.put("timezone", analyticsZone.getId());
			result.put("workingTimeEnabled", workingTime.enabled());
			result.put("workdayStart", workingTime.workdayStart().toString());
			result.put("workdayEnd", workingTime.workdayEnd().toString());
			result.put("workingDays", workingTime.workingDays().stream().map(DayOfWeek::name).toList());
			result.put("filters", filters.toMap());
			result.put("newAppeals", newAppeals);
			result.put("openTasks", openTasks);
			result.put("overdueSla", overdueSla);
			result.put("overdueDeadlines", overdueDeadlines);
			result.put("deadlineWarnings", deadlineWarnings);
			result.put("deadlineWarningMinutes", DEFAULT_DEADLINE_WARNING_MINUTES);
			result.put("unansweredMessages", unansweredMessages);
			result.put("avgFirstResponseSeconds", avgFirstResponseSeconds);
			result.put("avgCloseTimeSeconds", averageSeconds(closeTimeSeconds));
			result.put("unassignedTasks", unassignedTasks);
			result.put("closedTasks", closedTasks);
			result.put("reopenedTasks", reopenedTasks);
			result.put("closedByPeriod", toPeriodRows(closedByPeriodMap));
			result.put("reopenedByPeriod", toPeriodRows(reopenedByPeriodMap));
			result.put("hourlyLoad", toHourlyRows(hourlyLoadMap));
			result.put("operatorLoad", toOperatorRows(operatorLoadMap));
			result.put("taskTypeBreakdown", toBreakdownRows(taskTypeBreakdownMap));
			result.put("priorityBreakdown", toBreakdownRows(priorityBreakdownMap));
			result.put("executorBreakdown", toBreakdownRows(executorBreakdownMap));
			result.put("tagBreakdown", toBreakdownRows(tagBreakdownMap));
			return result;
		} finally {
			completeAnalyticsRequest(cancellationToken);
		}
	}


	public void cancelSummary(String requestKey) {
		String safeRequestKey = requestKey == null ? null : requestKey.trim();

		if (safeRequestKey == null || safeRequestKey.isBlank()) {
			return;
		}

		AnalyticsCancellationToken cancellationToken = activeAnalyticsRequests.get(safeRequestKey);

		if (cancellationToken != null) {
			cancellationToken.cancel();
		}
	}


	private long countOverdueSla(
			ZonedDateTime now,
			AnalyticsFilters filters,
			Map<Long, List<Object>> tagsByTaskId,
			Map<Long, Map<String, Object>> operatorLoadMap,
			Map<String, Map<String, Object>> taskTypeBreakdownMap,
			Map<String, Map<String, Object>> priorityBreakdownMap,
			Map<String, Map<String, Object>> executorBreakdownMap,
			Map<String, Map<String, Object>> tagBreakdownMap,
			AnalyticsCancellationToken cancellationToken
	) {
		List<TaskRepository.SlaAnalyticsRow> slaRows = taskRepository.findSlaAnalyticsRows(
				!filters.typeIds().isEmpty(),
				idsOrDummy(filters.typeIds()),
				!filters.priorityIds().isEmpty(),
				idsOrDummy(filters.priorityIds()),
				!filters.executorIds().isEmpty(),
				idsOrDummy(filters.executorIds()),
				!filters.tagIds().isEmpty(),
				idsOrDummy(filters.tagIds())
		);

		long overdueSla = 0L;

		for (TaskRepository.SlaAnalyticsRow row : slaRows) {
			checkAnalyticsCancelled(cancellationToken);

			Sla sla = row.getSla();
			if (sla == null) {
				continue;
			}

			ZonedDateTime deadline = slaService.deadline(sla);
			if (deadline == null || !deadline.isBefore(now)) {
				continue;
			}

			overdueSla++;

			if (row.getExecutor() != null) {
				incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "overdueSla");
			}

			incrementBreakdowns(
					taskTypeBreakdownMap,
					priorityBreakdownMap,
					executorBreakdownMap,
					tagBreakdownMap,
					row.getType(),
					row.getPriority(),
					row.getExecutor(),
					tagsByTaskId.getOrDefault(row.getId(), List.of()),
					"overdueSla"
			);
		}

		return overdueSla;
	}


	private Map<Long, List<Object>> getTagsByTaskId(AnalyticsFilters filters, AnalyticsCancellationToken cancellationToken) {
		List<TaskRepository.AnalyticsTaskTagRow> tagRows = taskRepository.findAnalyticsTaskTagRows(
				!filters.typeIds().isEmpty(),
				idsOrDummy(filters.typeIds()),
				!filters.priorityIds().isEmpty(),
				idsOrDummy(filters.priorityIds()),
				!filters.executorIds().isEmpty(),
				idsOrDummy(filters.executorIds()),
				!filters.tagIds().isEmpty(),
				idsOrDummy(filters.tagIds())
		);

		Map<Long, List<Object>> result = new LinkedHashMap<>();

		for (TaskRepository.AnalyticsTaskTagRow row : tagRows) {
			checkAnalyticsCancelled(cancellationToken);

			if (row.getTaskId() == null || row.getTag() == null) {
				continue;
			}

			result.computeIfAbsent(row.getTaskId(), ignored -> new ArrayList<>()).add(row.getTag());
		}

		return result;
	}


	private List<AnalyticsMessageRow> getClientMessageRows(
			ZonedDateTime from,
			ZonedDateTime to,
			AnalyticsCancellationToken cancellationToken
	) {
		List<MessageRepository.MessageAnalyticsRow> rows = messageRepository.findClientMessageAnalyticsRowsBetween(from, to);
		List<AnalyticsMessageRow> result = new ArrayList<>(rows.size());

		for (MessageRepository.MessageAnalyticsRow row : rows) {
			checkAnalyticsCancelled(cancellationToken);

			result.add(new AnalyticsMessageRow(
					row.getId(),
					row.getClientId(),
					row.getDate(),
					row.getSent(),
					row.getCommentFlag(),
					row.getDeleted(),
					row.getAnswerRequired()
			));
		}

		result.sort(Comparator
				.comparing(AnalyticsMessageRow::clientId, Comparator.nullsLast(Long::compareTo))
				.thenComparing(AnalyticsMessageRow::date, Comparator.nullsLast(ZonedDateTime::compareTo))
				.thenComparing(AnalyticsMessageRow::id, Comparator.nullsLast(Long::compareTo)));

		return result;
	}


	private List<Long> getFirstResponseSeconds(
			List<AnalyticsMessageRow> messages,
			AnalyticsWorkingTime workingTime,
			AnalyticsCancellationToken cancellationToken
	) {
		List<Long> result = new ArrayList<>();
		Long currentClientId = null;
		ZonedDateTime firstPendingIncomingMessageDate = null;

		for (AnalyticsMessageRow message : messages) {
			checkAnalyticsCancelled(cancellationToken);

			if (!Objects.equals(currentClientId, message.clientId())) {
				currentClientId = message.clientId();
				firstPendingIncomingMessageDate = null;
			}

			if (isIncomingMessage(message)) {
				if (firstPendingIncomingMessageDate == null) {
					firstPendingIncomingMessageDate = message.date();
				}
				continue;
			}

			if (isOutgoingOperatorMessage(message)
					&& firstPendingIncomingMessageDate != null
					&& message.date() != null
					&& message.date().isAfter(firstPendingIncomingMessageDate)) {
				result.add(getWorkingSeconds(firstPendingIncomingMessageDate, message.date(), workingTime, cancellationToken));
				firstPendingIncomingMessageDate = null;
			}
		}

		return result;
	}


	private long countUnansweredMessages(List<AnalyticsMessageRow> messages, AnalyticsCancellationToken cancellationToken) {
		long total = 0L;
		long pendingIncoming = 0L;
		Long currentClientId = null;

		for (AnalyticsMessageRow message : messages) {
			checkAnalyticsCancelled(cancellationToken);

			if (!Objects.equals(currentClientId, message.clientId())) {
				total += pendingIncoming;
				pendingIncoming = 0L;
				currentClientId = message.clientId();
			}

			if (isIncomingMessageRequiringAnswer(message)) {
				pendingIncoming++;
			} else if (isOutgoingOperatorMessage(message)) {
				pendingIncoming = 0L;
			}
		}

		return total + pendingIncoming;
	}


	private boolean isIncomingMessageRequiringAnswer(AnalyticsMessageRow message) {
		return isIncomingMessage(message)
				&& !AnswerRequired.ANSWER_NOT_REQUIRED.equals(message.answerRequired());
	}


	private boolean isIncomingMessage(AnalyticsMessageRow message) {
		return message != null
				&& Boolean.FALSE.equals(message.sent())
				&& !Boolean.TRUE.equals(message.comment())
				&& !Boolean.TRUE.equals(message.deleted());
	}


	private boolean isOutgoingOperatorMessage(AnalyticsMessageRow message) {
		return message != null
				&& Boolean.TRUE.equals(message.sent())
				&& !Boolean.TRUE.equals(message.comment())
				&& !Boolean.TRUE.equals(message.deleted());
	}


	private boolean isTaskDeadlineOverdue(ZonedDateTime deadline, ZonedDateTime now) {
		return deadline != null && deadline.isBefore(now);
	}


	private boolean isTaskDeadlineWarning(ZonedDateTime deadline, ZonedDateTime now, long warningMinutes) {
		if (deadline == null || deadline.isBefore(now)) {
			return false;
		}
		return !deadline.isAfter(now.plusMinutes(warningMinutes));
	}


	private boolean isBetween(ZonedDateTime date, ZonedDateTime from, ZonedDateTime to) {
		if (date == null || from == null || to == null) {
			return false;
		}
		ZonedDateTime zonedDate = date.withZoneSameInstant(from.getZone());
		return !zonedDate.isBefore(from) && !zonedDate.isAfter(to);
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
			Object type,
			Object priority,
			User executor,
			Collection<?> tags,
			String metric
	) {
		incrementBreakdown(taskTypeBreakdownMap, getEntityId(type), getEntityName(type, "Без типа"), metric);
		incrementBreakdown(priorityBreakdownMap, getEntityId(priority), getEntityName(priority, "Без приоритета"), metric);
		incrementBreakdown(executorBreakdownMap, executor == null ? null : executor.getId(), executor == null ? "Без исполнителя" : getUserDisplayName(executor), metric);

		Collection<?> safeTags = safeCollection(tags);
		if (safeTags.isEmpty()) {
			incrementBreakdown(tagBreakdownMap, null, "Без тегов", metric);
			return;
		}

		for (Object tag : safeTags) {
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


	private void incrementHourlyLoad(Map<Integer, Map<String, Object>> hourlyLoadMap, ZonedDateTime date, ZoneId zone, String metric) {
		if (date == null || zone == null) {
			return;
		}
		int hour = date.withZoneSameInstant(zone).getHour();
		Map<String, Object> row = hourlyLoadMap.get(hour);
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


	private Set<Long> idsOrDummy(Set<Long> ids) {
		return ids == null || ids.isEmpty() ? Set.of(EMPTY_GROUP_ID) : ids;
	}


	private List<AnalyticsEvent> getAutomationEvents(
			TriggerType triggerType,
			ZonedDateTime from,
			ZonedDateTime to,
			ZoneId zone,
			AnalyticsCancellationToken cancellationToken
	) {
		List<AnalyticsEvent> result = new ArrayList<>();
		if (from == null || to == null) {
			return result;
		}
		List<AutomationOutboxRepository.TaskReopenedAnalyticsRow> events =
				automationOutboxRepository.findTaskReopenedAnalyticsRows(
						triggerType.name(),
						from.toInstant(),
						to.toInstant()
				);
		for (AutomationOutboxRepository.TaskReopenedAnalyticsRow event : events) {
			checkAnalyticsCancelled(cancellationToken);
			if (event.getCreatedAt() == null || event.getTaskId() == null) {
				continue;
			}
			ZonedDateTime eventDate = event.getCreatedAt().atZone(zone);
			if (!isBetween(eventDate, from, to)) {
				continue;
			}
			result.add(new AnalyticsEvent(eventDate, event.getTaskId()));
		}
		return result;
	}


	private Long getEventTaskId(Object event) {
		Object directTask = firstGetterValue(event, "getTask");
		Long directTaskId = getEntityId(directTask);
		if (directTaskId != null) {
			return directTaskId;
		}
		Object payload = firstGetterValue(event,
				"getPayload",
				"getPayloadJson",
				"getData",
				"getBody",
				"getMessage"
		);
		return findTaskIdFromAny(payload);
	}


	private Long findTaskIdFromAny(Object value) {
		if (value == null) {
			return null;
		}

		if (value instanceof Map<?, ?> map) {
			Object taskValue = map.get("task");
			if (taskValue != null) {
				Long taskId = findTaskIdFromAny(taskValue);
				if (taskId != null) {
					return taskId;
				}
			}

			Long taskId = asLongOrNull(map.get("taskId"));
			if (taskId == null && taskValue instanceof Map<?, ?> taskMap) {
				taskId = asLongOrNull(taskMap.get("id"));
			}

			if (taskId != null) {
				return taskId;
			}
		}

		if (value instanceof CharSequence text) {
			return findTaskId(text.toString());
		}

		return getEntityId(value);
	}


	private Long findTaskId(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.replace("\\\"", "\"");
		List<Pattern> patterns = List.of(
				Pattern.compile("\"task\"\\s*:\\s*\\{[^}]*\"id\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
				Pattern.compile("\"taskId\"\\s*:\\s*(\\d+)", Pattern.CASE_INSENSITIVE),
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


	private ZonedDateTime getEventDate(Object event, ZoneId zone) {
		Object value = firstGetterValue(event,
				"getCreatedAt",
				"getCreatedDate",
				"getOccurredAt",
				"getProcessedAt",
				"getDate",
				"getTimestamp"
		);
		return asZonedDateTime(value, zone);
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


	private ZonedDateTime asZonedDateTime(Object value, ZoneId zone) {
		return switch (value) {
			case null -> null;
			case ZonedDateTime zonedDateTime -> zonedDateTime.withZoneSameInstant(zone);
			case Instant instant -> instant.atZone(zone);
			case LocalDateTime localDateTime -> localDateTime.atZone(zone);
			case Date date -> date.toInstant().atZone(zone);
			case CharSequence text -> parseZonedDateTime(text.toString(), zone);
			default -> null;
		};
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


	private String getPeriodLabel(ZonedDateTime date, String groupBy, ZoneId zone) {
		if (date == null) {
			return "";
		}
		ZonedDateTime zonedDate = date.withZoneSameInstant(zone);
		if ("WEEK".equalsIgnoreCase(groupBy)) {
			ZonedDateTime startOfWeek = zonedDate
					.minusDays(zonedDate.getDayOfWeek().getValue() - 1L)
					.toLocalDate()
					.atStartOfDay(zone);
			return startOfWeek.toLocalDate().toString();
		}
		return zonedDate.toLocalDate().toString();
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


	private ZonedDateTime parseZonedDateTime(String value, ZoneId zone) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim();
		try {
			return ZonedDateTime.parse(normalized).withZoneSameInstant(zone);
		} catch (Exception ignored) {
		}
		try {
			return Instant.parse(normalized).atZone(zone);
		} catch (Exception ignored) {
		}
		try {
			return LocalDateTime.parse(normalized).atZone(zone);
		} catch (Exception ignored) {
		}
		try {
			return LocalDate.parse(normalized).atStartOfDay(zone);
		} catch (Exception ignored) {
			return null;
		}
	}


	private static <T> Collection<T> safeCollection(Collection<T> collection) {
		return collection == null ? List.of() : collection;
	}

	private AnalyticsCancellationToken registerAnalyticsRequest(String requestKey) {
		String safeRequestKey = requestKey == null ? null : requestKey.trim();

		if (safeRequestKey == null || safeRequestKey.isBlank()) {
			return new AnalyticsCancellationToken(null);
		}

		AnalyticsCancellationToken cancellationToken = new AnalyticsCancellationToken(safeRequestKey);
		AnalyticsCancellationToken previousToken = activeAnalyticsRequests.put(safeRequestKey, cancellationToken);

		if (previousToken != null) {
			previousToken.cancel();
		}

		return cancellationToken;
	}


	private void completeAnalyticsRequest(AnalyticsCancellationToken cancellationToken) {
		if (cancellationToken == null || cancellationToken.requestKey() == null || cancellationToken.requestKey().isBlank()) {
			return;
		}
		activeAnalyticsRequests.remove(cancellationToken.requestKey(), cancellationToken);
	}


	private void checkAnalyticsCancelled(AnalyticsCancellationToken cancellationToken) {
		if (cancellationToken != null && cancellationToken.cancelled()) {
			throw new CancellationException("Analytics request was cancelled");
		}
	}


	private static final class AnalyticsCancellationToken {
		private final String requestKey;
		private volatile boolean cancelled;

		private AnalyticsCancellationToken(String requestKey) {
			this.requestKey = requestKey;
		}

		private String requestKey() {
			return requestKey;
		}

		private void cancel() {
			this.cancelled = true;
		}

		private boolean cancelled() {
			return cancelled || Thread.currentThread().isInterrupted();
		}
	}


	private record AnalyticsMessageRow(
			Long id,
			Long clientId,
			ZonedDateTime date,
			Boolean sent,
			Boolean comment,
			Boolean deleted,
			AnswerRequired answerRequired
	) {
	}


	private record AnalyticsEvent(ZonedDateTime date, Long taskId) {
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


	private AnalyticsWorkingTime getAnalyticsWorkingTime() {
		try {
			AppSettings settings = appSettingsService.getGeneralSettings();
			ZoneId zone = parseZoneOrDefault(settings == null ? null : settings.getTimezone());
			LocalTime workdayStart = parseTimeOrDefault(settings == null ? null : settings.getWorkdayStart(), LocalTime.of(9, 0));
			LocalTime workdayEnd = parseTimeOrDefault(settings == null ? null : settings.getWorkdayEnd(), LocalTime.of(18, 0));
			if (!workdayEnd.isAfter(workdayStart)) {
				workdayStart = LocalTime.of(9, 0);
				workdayEnd = LocalTime.of(18, 0);
			}
			EnumSet<DayOfWeek> workingDays = EnumSet.noneOf(DayOfWeek.class);
			if (settings == null || Boolean.TRUE.equals(settings.getMondayEnabled())) {
				workingDays.add(DayOfWeek.MONDAY);
			}
			if (settings == null || Boolean.TRUE.equals(settings.getTuesdayEnabled())) {
				workingDays.add(DayOfWeek.TUESDAY);
			}
			if (settings == null || Boolean.TRUE.equals(settings.getWednesdayEnabled())) {
				workingDays.add(DayOfWeek.WEDNESDAY);
			}
			if (settings == null || Boolean.TRUE.equals(settings.getThursdayEnabled())) {
				workingDays.add(DayOfWeek.THURSDAY);
			}
			if (settings == null || Boolean.TRUE.equals(settings.getFridayEnabled())) {
				workingDays.add(DayOfWeek.FRIDAY);
			}
			if (settings != null && Boolean.TRUE.equals(settings.getSaturdayEnabled())) {
				workingDays.add(DayOfWeek.SATURDAY);
			}
			if (settings != null && Boolean.TRUE.equals(settings.getSundayEnabled())) {
				workingDays.add(DayOfWeek.SUNDAY);
			}
			if (workingDays.isEmpty()) {
				workingDays.addAll(EnumSet.of(
						DayOfWeek.MONDAY,
						DayOfWeek.TUESDAY,
						DayOfWeek.WEDNESDAY,
						DayOfWeek.THURSDAY,
						DayOfWeek.FRIDAY
				));
			}
			return new AnalyticsWorkingTime(
					zone,
					settings == null || Boolean.TRUE.equals(settings.getWorkingTimeEnabled()),
					workdayStart,
					workdayEnd,
					workingDays
			);
		} catch (Exception ignored) {
			return new AnalyticsWorkingTime(
					ZoneId.systemDefault(),
					true,
					LocalTime.of(9, 0),
					LocalTime.of(18, 0),
					EnumSet.of(
							DayOfWeek.MONDAY,
							DayOfWeek.TUESDAY,
							DayOfWeek.WEDNESDAY,
							DayOfWeek.THURSDAY,
							DayOfWeek.FRIDAY
					)
			);
		}
	}


	private ZoneId parseZoneOrDefault(String timezone) {
		if (timezone == null || timezone.isBlank()) {
			return ZoneId.systemDefault();
		}
		try {
			return ZoneId.of(timezone);
		} catch (Exception ignored) {
			return ZoneId.systemDefault();
		}
	}


	private LocalTime parseTimeOrDefault(String value, LocalTime fallback) {
		if (value == null || value.isBlank()) {
			return fallback;
		}
		try {
			return LocalTime.parse(value);
		} catch (Exception ignored) {
			return fallback;
		}
	}


	private long getWorkingSeconds(
			ZonedDateTime start,
			ZonedDateTime end,
			AnalyticsWorkingTime workingTime,
			AnalyticsCancellationToken cancellationToken
	) {
		checkAnalyticsCancelled(cancellationToken);
		if (start == null || end == null || workingTime == null || end.isBefore(start)) {
			return 0L;
		}
		ZoneId zone = workingTime.zone();
		ZonedDateTime zonedStart = start.withZoneSameInstant(zone);
		ZonedDateTime zonedEnd = end.withZoneSameInstant(zone);
		if (!workingTime.enabled()) {
			return Duration.between(zonedStart, zonedEnd).getSeconds();
		}
		LocalDate currentDate = zonedStart.toLocalDate();
		LocalDate endDate = zonedEnd.toLocalDate();
		long seconds = 0L;
		while (!currentDate.isAfter(endDate)) {
			checkAnalyticsCancelled(cancellationToken);
			if (isWorkingDay(currentDate, workingTime)) {
				ZonedDateTime windowStart = currentDate
						.atTime(workingTime.workdayStart())
						.atZone(zone);
				ZonedDateTime windowEnd = currentDate
						.atTime(workingTime.workdayEnd())
						.atZone(zone);
				ZonedDateTime segmentStart = maxDateTime(zonedStart, windowStart);
				ZonedDateTime segmentEnd = minDateTime(zonedEnd, windowEnd);
				if (segmentEnd.isAfter(segmentStart)) {
					seconds += Duration.between(segmentStart, segmentEnd).getSeconds();
				}
			}
			currentDate = currentDate.plusDays(1);
		}
		return seconds;
	}


	private boolean isWorkingDay(LocalDate date, AnalyticsWorkingTime workingTime) {
		return date != null
				&& workingTime != null
				&& workingTime.workingDays().contains(date.getDayOfWeek());
	}


	private ZonedDateTime maxDateTime(ZonedDateTime left, ZonedDateTime right) {
		return left.isAfter(right) ? left : right;
	}


	private ZonedDateTime minDateTime(ZonedDateTime left, ZonedDateTime right) {
		return left.isBefore(right) ? left : right;
	}


	private record AnalyticsWorkingTime(
			ZoneId zone,
			boolean enabled,
			LocalTime workdayStart,
			LocalTime workdayEnd,
			EnumSet<DayOfWeek> workingDays
	) {
	}

}

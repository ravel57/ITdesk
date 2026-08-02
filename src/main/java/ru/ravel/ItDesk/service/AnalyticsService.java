package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.OlaStatus;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.TaskSupportLineStage;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.MessageRepository;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.repository.TaskSupportLineStageRepository;

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
	private final TaskSupportLineStageRepository taskSupportLineStageRepository;
	private final OlaWorkingTimeService olaWorkingTimeService;
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
		return getSummary(null, from, to, groupBy, typeIds, priorityIds, executorIds, tagIds, null);
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
			String tagIds,
			String supportLineIds
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
					parseIds(tagIds),
					parseIds(supportLineIds)
			);

			Set<Long> repositoryExecutorIds = filters.repositoryExecutorIds();
			List<TaskRepository.AnalyticsTaskRow> taskRows = taskRepository.findAnalyticsTaskRows(
					!filters.typeIds().isEmpty(),
					idsOrDummy(filters.typeIds()),
					!filters.priorityIds().isEmpty(),
					idsOrDummy(filters.priorityIds()),
					!repositoryExecutorIds.isEmpty(),
					idsOrDummy(repositoryExecutorIds),
					!filters.supportLineIds().isEmpty(),
					idsOrDummy(filters.supportLineIds()),
					!filters.tagIds().isEmpty(),
					idsOrDummy(filters.tagIds())
			);
			if (filters.executorFilterNeedsInMemory()) {
				taskRows = taskRows.stream()
						.filter(row -> filters.matchesExecutor(row.getExecutor()))
						.toList();
			}
			checkAnalyticsCancelled(cancellationToken);

			Map<Long, List<Object>> tagsByTaskId = getTagsByTaskId(filters, cancellationToken);
			Map<Long, TaskRepository.AnalyticsTaskRow> taskRowsById = new LinkedHashMap<>();
			Set<Long> filteredLinkedMessageIds = new HashSet<>();

			for (TaskRepository.AnalyticsTaskRow row : taskRows) {
				checkAnalyticsCancelled(cancellationToken);
				if (row.getId() != null) {
					taskRowsById.put(row.getId(), row);
				}
				if (row.getLinkedMessageId() != null) {
					filteredLinkedMessageIds.add(row.getLinkedMessageId());
				}
			}

			List<AnalyticsEvent> closedEvents = getAutomationEvents(
					TriggerType.TASK_CLOSED,
					safeFrom,
					safeTo,
					analyticsZone,
					cancellationToken
			);
			List<AnalyticsEvent> reopenedEvents = getAutomationEvents(
					TriggerType.TASK_REOPENED,
					safeFrom,
					safeTo,
					analyticsZone,
					cancellationToken
			);
			Set<Long> breakdownTaskIds = collectBreakdownTaskIds(
					taskRows,
					taskRowsById,
					closedEvents,
					reopenedEvents,
					safeFrom,
					safeTo,
					cancellationToken
			);

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
			long overdueOla = 0L;
			long olaWarnings = 0L;
			long unassignedTasks = 0L;

			for (TaskRepository.AnalyticsTaskRow row : taskRows) {
				checkAnalyticsCancelled(cancellationToken);
				Collection<?> tags = tagsByTaskId.getOrDefault(row.getId(), List.of());
				boolean includeInBreakdown = row.getId() != null && breakdownTaskIds.contains(row.getId());
				if (includeInBreakdown) {
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
				}
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
					incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "openTasks");
					if (includeInBreakdown) {
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
					}
					if (isOlaBreached(row, now)) {
						overdueOla++;
						incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "overdueOla");
						if (includeInBreakdown) {
							incrementBreakdowns(
								taskTypeBreakdownMap,
								priorityBreakdownMap,
								executorBreakdownMap,
								tagBreakdownMap,
								row.getType(),
								row.getPriority(),
								row.getExecutor(),
								tags,
								"overdueOla"
							);
						}
					} else if (isOlaWarning(row, now)) {
						olaWarnings++;
						incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "olaWarnings");
						if (includeInBreakdown) {
							incrementBreakdowns(
								taskTypeBreakdownMap,
								priorityBreakdownMap,
								executorBreakdownMap,
								tagBreakdownMap,
								row.getType(),
								row.getPriority(),
								row.getExecutor(),
								tags,
								"olaWarnings"
							);
						}
					}
					if (isTaskDeadlineOverdue(row.getDeadline(), now)) {
						overdueDeadlines++;
						incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "overdueDeadlines");
						if (includeInBreakdown) {
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
					}
					if (isTaskDeadlineWarning(row.getDeadline(), now, DEFAULT_DEADLINE_WARNING_MINUTES)) {
						deadlineWarnings++;
					}
					if (isUnassignedExecutor(row.getExecutor())) {
						unassignedTasks++;
						if (includeInBreakdown) {
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
					}
				}
			}
			long overdueSla = countOverdueSla(
					now,
					filters,
					breakdownTaskIds,
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
			// Для первого ответа нужен контекст до начала выбранного периода:
			// клиент мог написать раньше, а оператор ответить уже внутри периода.
			List<AnalyticsMessageRow> firstResponseMessages = getClientMessageRowsUntil(safeTo, cancellationToken);
			List<Long> firstResponseSeconds = getFirstResponseSeconds(
					firstResponseMessages,
					safeFrom,
					safeTo,
					filters.hasAny(),
					filteredLinkedMessageIds,
					operatorLoadMap,
					cancellationToken
			);
			long avgFirstResponseSeconds = averageSeconds(firstResponseSeconds);

			Set<Long> closedTaskIdsFromEvents = new HashSet<>();
			for (AnalyticsEvent closedEvent : closedEvents) {
				checkAnalyticsCancelled(cancellationToken);
				if (closedEvent.taskId() == null) {
					continue;
				}
				TaskRepository.AnalyticsTaskRow taskRow = taskRowsById.get(closedEvent.taskId());
				if (filters.hasAny() && taskRow == null) {
					continue;
				}
				ZonedDateTime eventDate = closedEvent.date();
				if (!isBetween(eventDate, safeFrom, safeTo)) {
					continue;
				}
				if (!closedTaskIdsFromEvents.add(closedEvent.taskId())) {
					continue;
				}
				closedTasks++;
				closedByPeriodMap.merge(getPeriodLabel(eventDate, safeGroupBy, analyticsZone), 1L, Long::sum);
				incrementHourlyLoad(hourlyLoadMap, eventDate, analyticsZone, "closedTasks");
				if (taskRow == null) {
					continue;
				}
				incrementOperatorLoad(operatorLoadMap, taskRow.getExecutor(), "closedTasks");
				incrementBreakdowns(
						taskTypeBreakdownMap,
						priorityBreakdownMap,
						executorBreakdownMap,
						tagBreakdownMap,
						taskRow.getType(),
						taskRow.getPriority(),
						taskRow.getExecutor(),
						tagsByTaskId.getOrDefault(taskRow.getId(), List.of()),
						"closedTasks"
				);
				if (taskRow.getCreatedAt() != null && eventDate != null && !eventDate.isBefore(taskRow.getCreatedAt())) {
					closeTimeSeconds.add(getWorkingSeconds(taskRow.getCreatedAt(), eventDate, workingTime, cancellationToken));
				}
			}

			for (TaskRepository.AnalyticsTaskRow row : taskRows) {
				checkAnalyticsCancelled(cancellationToken);
				if (!Boolean.TRUE.equals(row.getCompleted())) {
					continue;
				}
				if (row.getId() != null && closedTaskIdsFromEvents.contains(row.getId())) {
					continue;
				}
				if (!isBetween(row.getClosedAt(), safeFrom, safeTo)) {
					continue;
				}
				closedTasks++;
				closedByPeriodMap.merge(getPeriodLabel(row.getClosedAt(), safeGroupBy, analyticsZone), 1L, Long::sum);
				incrementHourlyLoad(hourlyLoadMap, row.getClosedAt(), analyticsZone, "closedTasks");
				incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "closedTasks");
				incrementBreakdowns(
						taskTypeBreakdownMap,
						priorityBreakdownMap,
						executorBreakdownMap,
						tagBreakdownMap,
						row.getType(),
						row.getPriority(),
						row.getExecutor(),
						tagsByTaskId.getOrDefault(row.getId(), List.of()),
						"closedTasks"
				);
				if (row.getCreatedAt() != null && row.getClosedAt() != null && !row.getClosedAt().isBefore(row.getCreatedAt())) {
					closeTimeSeconds.add(getWorkingSeconds(row.getCreatedAt(), row.getClosedAt(), workingTime, cancellationToken));
				}
			}

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
				incrementOperatorLoad(operatorLoadMap, taskRow.getExecutor(), "reopenedTasks");
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
			SupportLineAnalytics supportLineAnalytics = buildSupportLineAnalytics(
					taskRows,
					breakdownTaskIds,
					closedEvents,
					reopenedEvents,
					safeFrom,
					safeTo,
					now,
					filters,
					cancellationToken
			);

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
			result.put("overdueOla", overdueOla);
			result.put("olaWarnings", olaWarnings);
			result.put("avgLineTimeSeconds", supportLineAnalytics.avgLineTimeSeconds());
			result.put("deadlineWarningMinutes", DEFAULT_DEADLINE_WARNING_MINUTES);
			result.put("unansweredMessages", unansweredMessages);
			result.put("avgFirstResponseSeconds", avgFirstResponseSeconds);
			result.put("firstResponseCount", firstResponseSeconds.size());
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
			result.put("supportLineBreakdown", supportLineAnalytics.rows());
			result.put("lineLoad", supportLineAnalytics.rows());
			result.put("lineTransitions", supportLineAnalytics.transitions());
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


	private Set<Long> collectBreakdownTaskIds(
			List<TaskRepository.AnalyticsTaskRow> taskRows,
			Map<Long, TaskRepository.AnalyticsTaskRow> taskRowsById,
			List<AnalyticsEvent> closedEvents,
			List<AnalyticsEvent> reopenedEvents,
			ZonedDateTime from,
			ZonedDateTime to,
			AnalyticsCancellationToken cancellationToken
	) {
		Set<Long> result = new LinkedHashSet<>();

		for (TaskRepository.AnalyticsTaskRow row : taskRows) {
			checkAnalyticsCancelled(cancellationToken);
			if (row.getId() == null) {
				continue;
			}
			if (isBetween(row.getCreatedAt(), from, to) || isBetween(row.getClosedAt(), from, to)) {
				result.add(row.getId());
			}
		}

		addEventTaskIds(result, taskRowsById, closedEvents, from, to, cancellationToken);
		addEventTaskIds(result, taskRowsById, reopenedEvents, from, to, cancellationToken);
		return result;
	}


	private void addEventTaskIds(
			Set<Long> target,
			Map<Long, TaskRepository.AnalyticsTaskRow> taskRowsById,
			List<AnalyticsEvent> events,
			ZonedDateTime from,
			ZonedDateTime to,
			AnalyticsCancellationToken cancellationToken
	) {
		for (AnalyticsEvent event : events == null ? List.<AnalyticsEvent>of() : events) {
			checkAnalyticsCancelled(cancellationToken);
			if (event == null || event.taskId() == null || !taskRowsById.containsKey(event.taskId())) {
				continue;
			}
			if (isBetween(event.date(), from, to)) {
				target.add(event.taskId());
			}
		}
	}


	private long countOverdueSla(
			ZonedDateTime now,
			AnalyticsFilters filters,
			Set<Long> breakdownTaskIds,
			Map<Long, List<Object>> tagsByTaskId,
			Map<Long, Map<String, Object>> operatorLoadMap,
			Map<String, Map<String, Object>> taskTypeBreakdownMap,
			Map<String, Map<String, Object>> priorityBreakdownMap,
			Map<String, Map<String, Object>> executorBreakdownMap,
			Map<String, Map<String, Object>> tagBreakdownMap,
			AnalyticsCancellationToken cancellationToken
	) {
		Set<Long> repositoryExecutorIds = filters.repositoryExecutorIds();
		List<TaskRepository.SlaAnalyticsRow> slaRows = taskRepository.findSlaAnalyticsRows(
				!filters.typeIds().isEmpty(),
				idsOrDummy(filters.typeIds()),
				!filters.priorityIds().isEmpty(),
				idsOrDummy(filters.priorityIds()),
				!repositoryExecutorIds.isEmpty(),
				idsOrDummy(repositoryExecutorIds),
				!filters.supportLineIds().isEmpty(),
				idsOrDummy(filters.supportLineIds()),
				!filters.tagIds().isEmpty(),
				idsOrDummy(filters.tagIds())
		);

		long overdueSla = 0L;

		for (TaskRepository.SlaAnalyticsRow row : slaRows) {
			checkAnalyticsCancelled(cancellationToken);
			if (!filters.matchesExecutor(row.getExecutor())) {
				continue;
			}
			Sla sla = row.getSla();
			if (sla == null) {
				continue;
			}

			ZonedDateTime deadline = slaService.deadline(sla);
			if (deadline == null || !deadline.isBefore(now)) {
				continue;
			}

			overdueSla++;
			incrementOperatorLoad(operatorLoadMap, row.getExecutor(), "overdueSla");
			if (row.getId() != null && breakdownTaskIds.contains(row.getId())) {
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
		}

		return overdueSla;
	}


	private Map<Long, List<Object>> getTagsByTaskId(AnalyticsFilters filters, AnalyticsCancellationToken cancellationToken) {
		List<TaskRepository.AnalyticsTaskTagRow> tagRows = taskRepository.findAnalyticsTaskTagRows(
				!filters.typeIds().isEmpty(),
				idsOrDummy(filters.typeIds()),
				!filters.priorityIds().isEmpty(),
				idsOrDummy(filters.priorityIds()),
				!filters.repositoryExecutorIds().isEmpty(),
				idsOrDummy(filters.repositoryExecutorIds()),
				!filters.supportLineIds().isEmpty(),
				idsOrDummy(filters.supportLineIds()),
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
		return mapClientMessageRows(
				messageRepository.findClientMessageAnalyticsRowsBetween(from, to),
				cancellationToken
		);
	}


	private List<AnalyticsMessageRow> getClientMessageRowsUntil(
			ZonedDateTime to,
			AnalyticsCancellationToken cancellationToken
	) {
		return mapClientMessageRows(
				messageRepository.findClientMessageAnalyticsRowsUntil(to),
				cancellationToken
		);
	}


	private List<AnalyticsMessageRow> mapClientMessageRows(
			List<MessageRepository.MessageAnalyticsRow> rows,
			AnalyticsCancellationToken cancellationToken
	) {
		List<MessageRepository.MessageAnalyticsRow> safeRows = rows == null ? List.of() : rows;
		List<AnalyticsMessageRow> result = new ArrayList<>(safeRows.size());

		for (MessageRepository.MessageAnalyticsRow row : safeRows) {
			checkAnalyticsCancelled(cancellationToken);
			if (row == null) {
				continue;
			}

			result.add(new AnalyticsMessageRow(
					row.getId(),
					row.getClientId(),
					row.getDate(),
					row.getSent(),
					row.getCommentFlag(),
					row.getDeleted(),
					row.getAnswerRequired(),
					row.getSender()
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
			ZonedDateTime responseFrom,
			ZonedDateTime responseTo,
			boolean filterByLinkedMessageIds,
			Set<Long> linkedMessageIds,
			Map<Long, Map<String, Object>> operatorLoadMap,
			AnalyticsCancellationToken cancellationToken
	) {
		List<Long> result = new ArrayList<>();
		Set<Long> safeLinkedMessageIds = linkedMessageIds == null ? Set.of() : linkedMessageIds;
		Long currentClientId = null;
		ZonedDateTime firstPendingIncomingMessageDate = null;

		for (AnalyticsMessageRow message : messages) {
			checkAnalyticsCancelled(cancellationToken);

			if (!Objects.equals(currentClientId, message.clientId())) {
				currentClientId = message.clientId();
				firstPendingIncomingMessageDate = null;
			}

			boolean messageMatchesTaskFilters = !filterByLinkedMessageIds
					|| safeLinkedMessageIds.contains(message.id());

			if (isIncomingMessageAnswerNotRequired(message)) {
				if (messageMatchesTaskFilters) {
					firstPendingIncomingMessageDate = null;
				}
				continue;
			}

			if (isIncomingMessageRequiringAnswer(message)) {
				if (!messageMatchesTaskFilters) {
					continue;
				}
				if (firstPendingIncomingMessageDate == null) {
					firstPendingIncomingMessageDate = message.date();
				}
				continue;
			}

			if (isOutgoingOperatorMessage(message)
					&& firstPendingIncomingMessageDate != null
					&& message.date() != null
					&& message.date().isAfter(firstPendingIncomingMessageDate)) {
				// Первый ответ считается по фактически прошедшему времени.
				// Рабочий график не должен превращать реальный ответ вне рабочих часов в 0 секунд.
				if (isBetween(message.date(), responseFrom, responseTo)) {
					long responseSeconds = getElapsedSeconds(
							firstPendingIncomingMessageDate,
							message.date(),
							cancellationToken
					);
					result.add(responseSeconds);
					addOperatorFirstResponse(operatorLoadMap, message.sender(), responseSeconds);
				}
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
			if (isIncomingMessageAnswerNotRequired(message)) {
				pendingIncoming = 0L;
			} else if (isIncomingMessageRequiringAnswer(message)) {
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


	private boolean isIncomingMessageAnswerNotRequired(AnalyticsMessageRow message) {
		return isIncomingMessage(message)
				&& AnswerRequired.ANSWER_NOT_REQUIRED.equals(message.answerRequired());
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


	private boolean isOlaBreached(TaskRepository.AnalyticsTaskRow row, ZonedDateTime now) {
		if (row == null || row.getOlaDeadline() == null || now == null) {
			return false;
		}
		OlaStatus status = row.getOlaStatus();
		if (status == OlaStatus.DISABLED || status == OlaStatus.PAUSED || status == OlaStatus.COMPLETED) {
			return false;
		}
		return now.isAfter(row.getOlaDeadline());
	}


	private boolean isOlaWarning(TaskRepository.AnalyticsTaskRow row, ZonedDateTime now) {
		if (row == null || row.getOlaWarningAt() == null || row.getOlaDeadline() == null || now == null) {
			return false;
		}
		OlaStatus status = row.getOlaStatus();
		if (status == OlaStatus.DISABLED || status == OlaStatus.PAUSED || status == OlaStatus.COMPLETED) {
			return false;
		}
		return !now.isBefore(row.getOlaWarningAt()) && !now.isAfter(row.getOlaDeadline());
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
				.map(item -> {
					Map<String, Object> row = new LinkedHashMap<>(item);
					long firstResponseCount = asLong(row.remove("firstResponseCount"));
					long firstResponseTotalSeconds = asLong(row.remove("firstResponseTotalSeconds"));
					row.put(
							"avgFirstResponseSeconds",
							firstResponseCount == 0L
									? 0L
									: Math.round((double) firstResponseTotalSeconds / firstResponseCount)
					);
					return row;
				})
				.sorted(Comparator.comparingLong((Map<String, Object> item) ->
						asLong(item.get("openTasks"))
								+ asLong(item.get("closedTasks"))
								+ asLong(item.get("reopenedTasks"))
								+ asLong(item.get("overdueSla"))
								+ asLong(item.get("overdueDeadlines"))
				).reversed())
				.toList();
	}


	private Map<String, Object> getOrCreateOperatorLoadRow(
			Map<Long, Map<String, Object>> operatorLoadMap,
			User user
	) {
		Long key = user == null || user.getId() == null ? EMPTY_GROUP_ID : user.getId();
		String name = user == null || user.getId() == null ? "Без исполнителя" : getUserDisplayName(user);

		return operatorLoadMap.computeIfAbsent(key, userId -> {
			Map<String, Object> created = new LinkedHashMap<>();
			created.put("userId", key);
			created.put("name", name);
			created.put("openTasks", 0L);
			created.put("closedTasks", 0L);
			created.put("overdueSla", 0L);
			created.put("overdueDeadlines", 0L);
			created.put("overdueOla", 0L);
			created.put("olaWarnings", 0L);
			created.put("reopenedTasks", 0L);
			created.put("firstResponseCount", 0L);
			created.put("firstResponseTotalSeconds", 0L);
			return created;
		});
	}


	private void incrementOperatorLoad(Map<Long, Map<String, Object>> operatorLoadMap, User user, String metric) {
		Map<String, Object> row = getOrCreateOperatorLoadRow(operatorLoadMap, user);
		row.put(metric, asLong(row.get(metric)) + 1L);
	}


	private void addOperatorFirstResponse(
			Map<Long, Map<String, Object>> operatorLoadMap,
			User user,
			long responseSeconds
	) {
		Map<String, Object> row = getOrCreateOperatorLoadRow(operatorLoadMap, user);
		row.put("firstResponseCount", asLong(row.get("firstResponseCount")) + 1L);
		row.put(
				"firstResponseTotalSeconds",
				asLong(row.get("firstResponseTotalSeconds")) + Math.max(responseSeconds, 0L)
		);
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
		incrementBreakdown(
				executorBreakdownMap,
				isUnassignedExecutor(executor) ? null : executor.getId(),
				isUnassignedExecutor(executor) ? "Без исполнителя" : getUserDisplayName(executor),
				metric
		);

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
			created.put("overdueOla", 0L);
			created.put("olaWarnings", 0L);
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
		String firstname = Objects.toString(user.getFirstname(), "").trim();
		String lastname = Objects.toString(user.getLastname(), "").trim();
		String fullName = (firstname + " " + lastname).trim();

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


	private SupportLineAnalytics buildSupportLineAnalytics(
			List<TaskRepository.AnalyticsTaskRow> taskRows,
			Set<Long> breakdownTaskIds,
			List<AnalyticsEvent> closedEvents,
			List<AnalyticsEvent> reopenedEvents,
			ZonedDateTime from,
			ZonedDateTime to,
			ZonedDateTime now,
			AnalyticsFilters filters,
			AnalyticsCancellationToken cancellationToken
	) {
		Map<Long, Map<String, Object>> rowsByLineId = new LinkedHashMap<>();
		Map<Long, TaskRepository.AnalyticsTaskRow> rowsByTaskId = taskRows.stream()
				.filter(row -> row.getId() != null)
				.collect(java.util.stream.Collectors.toMap(
						TaskRepository.AnalyticsTaskRow::getId,
						row -> row,
						(first, second) -> first,
						LinkedHashMap::new
				));
		Set<Long> filteredTaskIds = rowsByTaskId.keySet();
		Set<Long> closedInPeriod = closedEvents.stream()
				.filter(event -> event.taskId() != null && isBetween(event.date(), from, to))
				.map(AnalyticsEvent::taskId)
				.filter(filteredTaskIds::contains)
				.collect(java.util.stream.Collectors.toSet());
		Map<Long, Long> reopenedCounts = reopenedEvents.stream()
				.filter(event -> event.taskId() != null && isBetween(event.date(), from, to))
				.filter(event -> filteredTaskIds.contains(event.taskId()))
				.collect(java.util.stream.Collectors.groupingBy(
						AnalyticsEvent::taskId,
						LinkedHashMap::new,
						java.util.stream.Collectors.counting()
				));

		for (TaskRepository.AnalyticsTaskRow taskRow : taskRows) {
			checkAnalyticsCancelled(cancellationToken);
			Map<String, Object> lineRow = getOrCreateSupportLineRow(rowsByLineId, taskRow.getSupportLine());
			boolean includeInBreakdown = taskRow.getId() != null && breakdownTaskIds.contains(taskRow.getId());
			if (includeInBreakdown) {
				incrementMetric(lineRow, "totalTasks");
			}
			if (isBetween(taskRow.getCreatedAt(), from, to)) {
				incrementMetric(lineRow, "createdTasks");
			}
			if (!Boolean.TRUE.equals(taskRow.getCompleted())) {
				incrementMetric(lineRow, "openTasks");
				if (isUnassignedExecutor(taskRow.getExecutor())) {
					incrementMetric(lineRow, "unassignedTasks");
				}
				if (isTaskDeadlineOverdue(taskRow.getDeadline(), now)) {
					incrementMetric(lineRow, "overdueDeadlines");
				}
				if (isOlaBreached(taskRow, now)) {
					incrementMetric(lineRow, "overdueOla");
				} else if (isOlaWarning(taskRow, now)) {
					incrementMetric(lineRow, "olaWarnings");
				}
			}
			if (closedInPeriod.contains(taskRow.getId())
					|| (Boolean.TRUE.equals(taskRow.getCompleted()) && isBetween(taskRow.getClosedAt(), from, to))) {
				incrementMetric(lineRow, "closedTasks");
			}
			long reopened = reopenedCounts.getOrDefault(taskRow.getId(), 0L);
			if (reopened > 0) {
				lineRow.put("reopenedTasks", asLong(lineRow.get("reopenedTasks")) + reopened);
			}
		}

		Set<Long> repositoryExecutorIds = filters.repositoryExecutorIds();
		List<TaskRepository.SlaAnalyticsRow> slaRows = taskRepository.findSlaAnalyticsRows(
				!filters.typeIds().isEmpty(),
				idsOrDummy(filters.typeIds()),
				!filters.priorityIds().isEmpty(),
				idsOrDummy(filters.priorityIds()),
				!repositoryExecutorIds.isEmpty(),
				idsOrDummy(repositoryExecutorIds),
				!filters.supportLineIds().isEmpty(),
				idsOrDummy(filters.supportLineIds()),
				!filters.tagIds().isEmpty(),
				idsOrDummy(filters.tagIds())
		);
		for (TaskRepository.SlaAnalyticsRow slaRow : slaRows) {
			checkAnalyticsCancelled(cancellationToken);
			if (!filters.matchesExecutor(slaRow.getExecutor()) || !breakdownTaskIds.contains(slaRow.getId())) {
				continue;
			}
			ZonedDateTime deadline = slaRow.getSla() == null ? null : slaService.deadline(slaRow.getSla());
			if (deadline != null && deadline.isBefore(now)) {
				incrementMetric(getOrCreateSupportLineRow(rowsByLineId, slaRow.getSupportLine()), "overdueSla");
			}
		}

		List<TaskSupportLineStage> stages = taskSupportLineStageRepository.findOverlappingPeriod(
				from,
				to,
				!filters.supportLineIds().isEmpty(),
				idsOrDummy(filters.supportLineIds())
		).stream()
				.filter(stage -> stage.getTask() != null && stage.getTask().getId() != null)
				.filter(stage -> filteredTaskIds.contains(stage.getTask().getId()))
				.toList();

		long allLineSeconds = 0L;
		long allLineStages = 0L;
		Set<String> countedTaskLineKeys = new HashSet<>();
		Map<Long, List<TaskSupportLineStage>> stagesByTask = new LinkedHashMap<>();
		for (TaskSupportLineStage stage : stages) {
			checkAnalyticsCancelled(cancellationToken);
			ZonedDateTime stageStart = maxDateTime(stage.getEnteredAt(), from);
			ZonedDateTime rawEnd = stage.getLeftAt() == null ? minDateTime(now, to) : minDateTime(stage.getLeftAt(), to);
			long seconds = calculateStageSeconds(stage, stageStart, rawEnd);
			Map<String, Object> lineRow = getOrCreateSupportLineRow(rowsByLineId, stage.getSupportLine());
			lineRow.put("lineTimeSecondsTotal", asLong(lineRow.get("lineTimeSecondsTotal")) + seconds);
			String taskLineKey = stage.getTask().getId() + ":" + getSupportLineId(stage.getSupportLine());
			if (countedTaskLineKeys.add(taskLineKey)) {
				lineRow.put("lineStageCount", asLong(lineRow.get("lineStageCount")) + 1L);
				allLineStages++;
			}
			allLineSeconds += seconds;
			stagesByTask.computeIfAbsent(stage.getTask().getId(), ignored -> new ArrayList<>()).add(stage);
		}

		Map<String, Map<String, Object>> transitionMap = new LinkedHashMap<>();
		for (List<TaskSupportLineStage> taskStages : stagesByTask.values()) {
			taskStages.sort(Comparator.comparing(TaskSupportLineStage::getEnteredAt, Comparator.nullsLast(ZonedDateTime::compareTo)));
			for (int index = 1; index < taskStages.size(); index++) {
				TaskSupportLineStage previous = taskStages.get(index - 1);
				TaskSupportLineStage current = taskStages.get(index);
				if (!isBetween(current.getEnteredAt(), from, to)) {
					continue;
				}
				Long fromId = getSupportLineId(previous.getSupportLine());
				Long toId = getSupportLineId(current.getSupportLine());
				if (Objects.equals(fromId, toId)) {
					continue;
				}
				String key = fromId + ":" + toId;
				Map<String, Object> transition = transitionMap.computeIfAbsent(key, ignored -> {
					Map<String, Object> created = new LinkedHashMap<>();
					created.put("key", key);
					created.put("fromLineId", fromId);
					created.put("fromLine", getSupportLineName(previous.getSupportLine()));
					created.put("toLineId", toId);
					created.put("toLine", getSupportLineName(current.getSupportLine()));
					created.put("count", 0L);
					created.put("transitionSecondsTotal", 0L);
					return created;
				});
				long previousSeconds = calculateStageSeconds(
						previous,
						previous.getEnteredAt(),
						Objects.requireNonNullElse(previous.getLeftAt(), current.getEnteredAt())
				);
				transition.put("count", asLong(transition.get("count")) + 1L);
				transition.put("transitionSecondsTotal", asLong(transition.get("transitionSecondsTotal")) + previousSeconds);
			}
		}

		List<Map<String, Object>> rows = rowsByLineId.values().stream()
				.map(row -> {
					Map<String, Object> result = new LinkedHashMap<>(row);
					long stageCount = asLong(result.remove("lineStageCount"));
					long secondsTotal = asLong(result.remove("lineTimeSecondsTotal"));
					result.put("avgLineTimeSeconds", stageCount == 0 ? 0L : Math.round((double) secondsTotal / stageCount));
					return result;
				})
				.sorted(Comparator.comparingLong((Map<String, Object> row) -> asLong(row.get("openTasks"))).reversed()
						.thenComparing(row -> Objects.toString(row.get("name"), "")))
				.toList();
		List<Map<String, Object>> transitions = transitionMap.values().stream()
				.map(row -> {
					Map<String, Object> result = new LinkedHashMap<>(row);
					long count = asLong(result.get("count"));
					long totalSeconds = asLong(result.remove("transitionSecondsTotal"));
					result.put("avgTransitionSeconds", count == 0 ? 0L : Math.round((double) totalSeconds / count));
					return result;
				})
				.sorted(Comparator.comparingLong((Map<String, Object> row) -> asLong(row.get("count"))).reversed())
				.toList();
		long average = allLineStages == 0L ? 0L : Math.round((double) allLineSeconds / allLineStages);
		return new SupportLineAnalytics(average, rows, transitions);
	}


	private Map<String, Object> getOrCreateSupportLineRow(
			Map<Long, Map<String, Object>> rowsByLineId,
			SupportLine line
	) {
		Long lineId = getSupportLineId(line);
		return rowsByLineId.computeIfAbsent(lineId, ignored -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("key", lineId);
			row.put("id", lineId);
			row.put("supportLineId", lineId);
			row.put("name", getSupportLineName(line));
			row.put("level", line == null ? null : line.getLevel());
			row.put("totalTasks", 0L);
			row.put("createdTasks", 0L);
			row.put("openTasks", 0L);
			row.put("closedTasks", 0L);
			row.put("reopenedTasks", 0L);
			row.put("overdueSla", 0L);
			row.put("overdueDeadlines", 0L);
			row.put("unassignedTasks", 0L);
			row.put("overdueOla", 0L);
			row.put("olaWarnings", 0L);
			row.put("lineTimeSecondsTotal", 0L);
			row.put("lineStageCount", 0L);
			return row;
		});
	}


	private void incrementMetric(Map<String, Object> row, String metric) {
		row.put(metric, asLong(row.get(metric)) + 1L);
	}


	private Long getSupportLineId(SupportLine line) {
		return line == null || line.getId() == null ? EMPTY_GROUP_ID : line.getId();
	}


	private String getSupportLineName(SupportLine line) {
		return line == null || line.getName() == null || line.getName().isBlank() ? "Без линии" : line.getName();
	}


	private long calculateStageSeconds(TaskSupportLineStage stage, ZonedDateTime start, ZonedDateTime end) {
		if (stage == null || start == null || end == null || !end.isAfter(start)) {
			return 0L;
		}
		boolean useWorkingTime = Boolean.TRUE.equals(stage.getUseWorkingTime());
		long seconds = useWorkingTime
				? olaWorkingTimeService.secondsBetween(start, end, true)
				: Duration.between(start, end).getSeconds();
		long pausedSeconds = Objects.requireNonNullElse(stage.getPausedSeconds(), 0L);
		Task stageTask = stage.getTask();
		if (stage.getLeftAt() == null && stageTask != null && stageTask.getOlaPausedAt() != null) {
			ZonedDateTime pauseStart = stageTask.getOlaPausedAt().isAfter(start)
					? stageTask.getOlaPausedAt()
					: start;
			if (end.isAfter(pauseStart)) {
				pausedSeconds += useWorkingTime
						? olaWorkingTimeService.secondsBetween(pauseStart, end, true)
						: Duration.between(pauseStart, end).getSeconds();
			}
		}
		return Math.max(0L, seconds - pausedSeconds);
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


	private record SupportLineAnalytics(
			long avgLineTimeSeconds,
			List<Map<String, Object>> rows,
			List<Map<String, Object>> transitions
	) {
	}


	private record AnalyticsMessageRow(
			Long id,
			Long clientId,
			ZonedDateTime date,
			Boolean sent,
			Boolean comment,
			Boolean deleted,
			AnswerRequired answerRequired,
			User sender
	) {
	}


	private record AnalyticsEvent(ZonedDateTime date, Long taskId) {
	}


	private record AnalyticsFilters(
			Set<Long> typeIds,
			Set<Long> priorityIds,
			Set<Long> executorIds,
			Set<Long> tagIds,
			Set<Long> supportLineIds
	) {
		private boolean hasAny() {
			return !typeIds.isEmpty() || !priorityIds.isEmpty() || !executorIds.isEmpty()
					|| !tagIds.isEmpty() || !supportLineIds.isEmpty();
		}

		private boolean executorFilterNeedsInMemory() {
			return executorIds.contains(EMPTY_GROUP_ID);
		}

		private Set<Long> repositoryExecutorIds() {
			if (executorFilterNeedsInMemory()) {
				return Set.of();
			}
			return executorIds;
		}

		private boolean matchesExecutor(User executor) {
			if (executorIds.isEmpty()) {
				return true;
			}
			if (isUnassignedExecutor(executor)) {
				return executorIds.contains(EMPTY_GROUP_ID);
			}
			return executorIds.contains(executor.getId());
		}

		private Map<String, Object> toMap() {
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("typeIds", typeIds);
			result.put("priorityIds", priorityIds);
			result.put("executorIds", executorIds);
			result.put("tagIds", tagIds);
			result.put("supportLineIds", supportLineIds);
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


	private long getElapsedSeconds(
			ZonedDateTime start,
			ZonedDateTime end,
			AnalyticsCancellationToken cancellationToken
	) {
		checkAnalyticsCancelled(cancellationToken);
		if (start == null || end == null || !end.isAfter(start)) {
			return 0L;
		}

		// Duration#getSeconds отбрасывает доли секунды. Положительный ответ
		// всё равно должен участвовать в статистике хотя бы как 1 секунда.
		return Math.max(1L, Duration.between(start, end).getSeconds());
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


	private static boolean isUnassignedExecutor(User user) {
		return user == null || user.getId() == null;
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

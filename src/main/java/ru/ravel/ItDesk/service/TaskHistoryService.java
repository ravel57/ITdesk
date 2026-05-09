package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.TaskHistoryChangeDto;
import ru.ravel.ItDesk.dto.TaskHistoryItemDto;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class TaskHistoryService {

	private final AutomationOutboxRepository automationOutboxRepository;
	private static final DateTimeFormatter HISTORY_DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");


	@Transactional(readOnly = true)
	public List<TaskHistoryItemDto> getTaskHistory(Long taskId) {
		if (taskId == null) {
			return List.of();
		}
		return automationOutboxRepository.findTaskHistoryEvents(taskId).stream()
				.map(this::toDto)
				.toList();
	}


	private TaskHistoryItemDto toDto(Event event) {
		TriggerType triggerType = event.getTriggerType();
		JsonNode payload = event.getPayload();
		List<TaskHistoryChangeDto> changes = getChanges(triggerType, payload);
		return TaskHistoryItemDto.builder()
				.id(event.getId())
				.triggerType(triggerType)
				.title(getTitle(triggerType))
				.description(getDescription(triggerType, payload, changes))
				.createdAt(event.getCreatedAt())
				.actorUserId(event.getActorUserId())
				.actorUsername(event.getActorUsername())
				.actorDisplayName(event.getActorDisplayName())
				.actorType(event.getActorType())
				.changes(changes)
				.meta(Map.of("payload", payload == null ? "" : payload.toString()))
				.build();
	}


	private String getTitle(TriggerType triggerType) {
		if (triggerType == null) {
			return "Изменение заявки";
		}
		return switch (triggerType) {
			case TASK_CREATED -> "Заявка создана";
			case TASK_UPDATED -> "Заявка обновлена";
			case TASK_STATUS_CHANGED -> "Изменен статус";
			case TASK_PRIORITY_CHANGED -> "Изменен приоритет";
			case TASK_ASSIGNEE_CHANGED, TASK_EXECUTOR_CHANGED -> "Изменен исполнитель";
			case TASK_DUE_DATE_CHANGED -> "Изменен дедлайн";
			case TASK_CLOSED, TASK_COMPLETED -> "Заявка закрыта";
			case TASK_REOPENED -> "Заявка возвращена в работу";
			case TASK_TAG_ADDED -> "Добавлен тег";
			case TASK_TAG_REMOVED -> "Удален тег";
			case TASK_COMMENT_ADDED -> "Добавлен комментарий";
			case TASK_COMMENT_DELETED -> "Удален комментарий";
			default -> "Изменение заявки";
		};
	}


	private String getDescription(TriggerType triggerType, JsonNode payload, List<TaskHistoryChangeDto> changes) {
		if (changes != null && !changes.isEmpty()) {
			if (changes.size() == 1) {
				TaskHistoryChangeDto change = changes.getFirst();
				return "%s: %s → %s".formatted(
						change.getLabel(),
						change.getOldValue(),
						change.getNewValue()
				);
			}

			return "Изменено полей: " + changes.size();
		}

		if (triggerType == null || payload == null || payload.isNull()) {
			return "";
		}

		return switch (triggerType) {
			case TASK_STATUS_CHANGED -> "Статус: %s → %s".formatted(
					getName(payload.path("oldStatus")),
					getName(payload.path("newStatus"))
			);

			case TASK_PRIORITY_CHANGED -> "Приоритет: %s → %s".formatted(
					getName(payload.path("oldPriority")),
					getName(payload.path("newPriority"))
			);

			case TASK_ASSIGNEE_CHANGED, TASK_EXECUTOR_CHANGED -> "Исполнитель: %s → %s".formatted(
					getUserName(payload.path("oldExecutor")),
					getUserName(payload.path("newExecutor"))
			);

			case TASK_DUE_DATE_CHANGED -> "Дедлайн: %s → %s".formatted(
					formatNullable(payload.path("oldDeadline")),
					formatNullable(payload.path("newDeadline"))
			);

			case TASK_TAG_ADDED, TASK_TAG_REMOVED -> "Тег: %s".formatted(
					getName(payload.path("tag"))
			);

			case TASK_CREATED -> "Создана заявка «%s»".formatted(
					getName(payload.path("task"))
			);

			case TASK_CLOSED, TASK_COMPLETED -> "Заявка закрыта";

			case TASK_REOPENED -> "Заявка возвращена в работу";

			case TASK_UPDATED -> "Обновлены данные заявки";

			default -> "";
		};
	}


	private String getName(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "—";
		}
		String name = node.path("name").asText("");
		if (!name.isBlank()) {
			return name;
		}
		return node.asText("—");
	}


	private String getUserName(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "Без исполнителя";
		}
		String lastname = node.path("lastname").asText("");
		String firstname = node.path("firstname").asText("");
		String username = node.path("username").asText("");
		String fullName = (lastname + " " + firstname).trim();
		if (!fullName.isBlank()) {
			return fullName;
		}
		if (!username.isBlank()) {
			return username;
		}
		return "Без имени";
	}


	private String formatNullable(JsonNode node) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return "не задан";
		}
		if (node.isNumber()) {
			long value = node.asLong();
			Instant instant = value > 10_000_000_000L
					? Instant.ofEpochMilli(value)
					: Instant.ofEpochSecond(value);
			return HISTORY_DATE_FORMATTER.format(
					ZonedDateTime.ofInstant(instant, ZoneId.systemDefault())
			);
		}
		if (node.isArray() && node.size() >= 5) {
			try {
				LocalDateTime dateTime = LocalDateTime.of(
						node.get(0).asInt(),
						node.get(1).asInt(),
						node.get(2).asInt(),
						node.get(3).asInt(),
						node.get(4).asInt(),
						node.size() >= 6 ? node.get(5).asInt() : 0
				);
				return HISTORY_DATE_FORMATTER.format(dateTime);
			} catch (Exception ignored) {
				return node.toString();
			}
		}
		String value = node.asText("");
		if (value.isBlank()) {
			return "не задан";
		}
		try {
			return HISTORY_DATE_FORMATTER.format(ZonedDateTime.parse(value));
		} catch (Exception ignored) {
		}
		try {
			return HISTORY_DATE_FORMATTER.format(LocalDateTime.parse(value));
		} catch (Exception ignored) {
		}

		return value;
	}


	private List<TaskHistoryChangeDto> getChanges(TriggerType triggerType, JsonNode payload) {
		if (triggerType == null || payload == null || payload.isNull()) {
			return List.of();
		}
		return switch (triggerType) {
			case TASK_STATUS_CHANGED -> List.of(change(
					"status",
					"Статус",
					getName(payload.path("oldStatus")),
					getName(payload.path("newStatus"))
			));

			case TASK_PRIORITY_CHANGED -> List.of(change(
					"priority",
					"Приоритет",
					getName(payload.path("oldPriority")),
					getName(payload.path("newPriority"))
			));

			case TASK_ASSIGNEE_CHANGED, TASK_EXECUTOR_CHANGED -> List.of(change(
					"executor",
					"Исполнитель",
					getUserName(payload.path("oldExecutor")),
					getUserName(payload.path("newExecutor"))
			));

			case TASK_DUE_DATE_CHANGED -> List.of(change(
					"deadline",
					"Дедлайн",
					formatNullable(payload.path("oldDeadline")),
					formatNullable(payload.path("newDeadline"))
			));

			case TASK_TAG_ADDED -> List.of(change(
					"tags",
					"Теги",
					"—",
					getName(payload.path("tag"))
			));

			case TASK_TAG_REMOVED -> List.of(change(
					"tags",
					"Теги",
					getName(payload.path("tag")),
					"—"
			));

			default -> extractGenericChanges(payload);
		};
	}


	private TaskHistoryChangeDto change(String field, String label, String oldValue, String newValue) {
		return TaskHistoryChangeDto.builder()
				.field(field)
				.label(label)
				.oldValue(oldValue == null || oldValue.isBlank() ? "—" : oldValue)
				.newValue(newValue == null || newValue.isBlank() ? "—" : newValue)
				.build();
	}


	private List<TaskHistoryChangeDto> extractGenericChanges(JsonNode payload) {
		JsonNode changesNode = payload.path("changes");
		if (!changesNode.isArray()) {
			return List.of();
		}
		List<TaskHistoryChangeDto> changes = new ArrayList<>();
		for (JsonNode node : changesNode) {
			changes.add(change(
					node.path("field").asText(""),
					node.path("label").asText("Поле"),
					node.path("oldValue").asText("—"),
					node.path("newValue").asText("—")
			));
		}
		return changes;
	}
}
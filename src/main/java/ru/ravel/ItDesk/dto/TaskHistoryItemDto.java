package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import ru.ravel.ItDesk.model.ActorType;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskHistoryItemDto {

	private Long id;

	private TriggerType triggerType;

	private String title;

	private String description;

	private Instant createdAt;

	private Long actorUserId;

	private String actorUsername;

	private String actorDisplayName;

	private ActorType actorType;

	private List<TaskHistoryChangeDto> changes;

	private Map<String, Object> meta;
}
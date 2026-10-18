package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskUpdatedDto {
	private Long clientId;
	private Map<String, Object> task;


	/** Копия события без внутреннего чата заявки — для менеджера организации (OBSERVER). */
	public TaskUpdatedDto withoutTaskMessages() {
		return new TaskUpdatedDto(clientId, withoutTaskMessages(task));
	}


	/** Копия DTO заявки с пустым {@code messages}; исходная map не меняется. */
	public static Map<String, Object> withoutTaskMessages(Map<String, Object> task) {
		if (task == null) {
			return null;
		}
		Map<String, Object> copy = new LinkedHashMap<>(task);
		copy.put("messages", List.of());
		return copy;
	}
}

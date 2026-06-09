package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskUpdatedDto {
	private Long clientId;
	private Map<String, Object> task;
}
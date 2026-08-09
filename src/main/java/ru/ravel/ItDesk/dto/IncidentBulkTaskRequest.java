package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.util.List;

@Data
public class IncidentBulkTaskRequest {
	private List<Long> taskIds;
	private String operation;
}

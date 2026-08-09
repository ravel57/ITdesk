package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.util.List;

@Data
public class IncidentTaskIdsRequest {
	private List<Long> taskIds;
	private Long primaryTaskId;
}

package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.util.List;

@Data
public class IncidentPageRequest {
	private Integer page;
	private Integer size;
	private String search;
	private Boolean includeClosed;
	private Boolean overdueOnly;
	private List<String> severities;
	private List<String> statuses;
	private List<Long> organizationIds;
	private List<Long> serviceIds;
	private List<Long> leaderIds;
}

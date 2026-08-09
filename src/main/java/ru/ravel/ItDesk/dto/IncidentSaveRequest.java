package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.time.ZonedDateTime;
import java.util.List;

@Data
public class IncidentSaveRequest {
	private String title;
	private String description;
	private String publicDescription;
	private String impactSummary;
	private String serviceName;
	private List<Long> serviceIds;
	private String workaround;
	private String rootCause;
	private String resolution;
	private String postmortemSummary;
	private String severity;
	private String status;
	private Long leaderId;
	private Long technicalOwnerId;
	private Long communicationOwnerId;
	private Long supportLineId;
	private List<Long> participantIds;
	private List<Long> observerIds;
	private List<Long> organizationIds;
	private List<Long> clientIds;
	private List<Long> taskIds;
	private Long primaryTaskId;
	private ZonedDateTime actualStartedAt;
	private ZonedDateTime detectedAt;
	private ZonedDateTime declaredAt;
	private ZonedDateTime mitigatedAt;
	private ZonedDateTime restoredAt;
	private ZonedDateTime closedAt;
	private ZonedDateTime nextUpdateAt;
	private Integer updateIntervalMinutes;
}

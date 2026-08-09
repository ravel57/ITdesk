package ru.ravel.ItDesk.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class OrganizationServiceAssignmentRequest {
	private Long organizationId;
	private Long serviceId;
	private String contractNumber;
	private String tariffName;
	private LocalDate validFrom;
	private LocalDate validTo;
	private String supportCalendar;
	private String slaPolicyName;
	private Integer firstResponseSlaMinutes;
	private Integer resolutionSlaMinutes;
	private List<String> availableChannels;
	private Boolean active;
	private Boolean defaultForOrganization;
	private String notes;
}

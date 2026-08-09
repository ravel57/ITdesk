package ru.ravel.ItDesk.dto;

import lombok.Data;
import ru.ravel.ItDesk.model.ServiceOlaStage;
import ru.ravel.ItDesk.model.ServicePriorityRule;

import java.util.List;
import java.util.Map;

@Data
public class ServiceSaveRequest {
	private String code;
	private String name;
	private String description;
	private String monitoringUrl;
	private Boolean active;
	private Integer orderNumber;
	private Long criticalityId;
	private Long ownerId;
	private Long defaultSupportLineId;
	private Long defaultPriorityId;
	private List<Long> taskTypeIds;
	private List<Long> executorIds;
	private List<Long> dependencyIds;
	private Integer firstResponseSlaMinutes;
	private Integer resolutionSlaMinutes;
	private String supportCalendar;
	private Boolean pauseSlaOnWaitingClient;
	private Boolean autoAssignDefaultLine;
	private List<ServicePriorityRule> priorityRules;
	private List<ServiceOlaStage> olaStages;
	private Long zabbixIntegrationId;
	private Boolean zabbixEnabled;
	private Map<String, String> monitoringIdentifiers;
}

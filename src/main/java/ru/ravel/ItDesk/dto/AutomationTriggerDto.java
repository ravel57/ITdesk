package ru.ravel.ItDesk.dto;

import java.time.ZonedDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AutomationTriggerDto {
	private Long id;
	private String name;
	private String description;
	private String triggerType;
	private String expression;
	private String action;
	private String elseAction;
	private String workflowDefinition;
	private Integer workflowVersion;
	private Integer orderNumber;
	private String automationRuleStatus;
	private Boolean stopProcessing;
	private Long matchCount;
	private ZonedDateTime lastMatchedAt;
	private Long failureCount;
	private ZonedDateTime lastFailedAt;
	private String lastError;
}
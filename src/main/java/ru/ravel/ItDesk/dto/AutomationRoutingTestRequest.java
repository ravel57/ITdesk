package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AutomationRoutingTestRequest {
	private Long taskId;
	private String expression;
	private String action;
	private String elseAction;
	private String workflowDefinition;
}
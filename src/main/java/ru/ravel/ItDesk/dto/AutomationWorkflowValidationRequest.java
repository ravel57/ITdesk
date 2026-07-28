package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AutomationWorkflowValidationRequest {
	private String workflowDefinition;
}
package ru.ravel.ItDesk.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public final class AutomationTriggerDto {
	private Long id;
	private String name;
	private String description;
	private String triggerType;
	private String expression;
	private String action;
	private Integer orderNumber;
}

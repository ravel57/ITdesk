package ru.ravel.ItDesk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServicePriorityRule {
	private String id;
	private String name;
	private Long taskTypeId;
	private String keyword;
	private Long priorityId;
	@Builder.Default
	private Integer orderNumber = 0;
	@Builder.Default
	private Boolean active = true;
}

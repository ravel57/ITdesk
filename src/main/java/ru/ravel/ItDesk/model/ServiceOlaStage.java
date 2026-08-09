package ru.ravel.ItDesk.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceOlaStage {
	private Long supportLineId;
	@Builder.Default
	private Integer orderNumber = 0;
	private Long durationSeconds;
	@Builder.Default
	private Boolean useWorkingTime = true;
	@Builder.Default
	private Integer warningPercent = 80;
	@Builder.Default
	private Boolean active = true;
}

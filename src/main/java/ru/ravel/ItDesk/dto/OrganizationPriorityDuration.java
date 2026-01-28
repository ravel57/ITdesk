package ru.ravel.ItDesk.dto;

import lombok.Data;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.model.SlaUnit;

import java.math.BigDecimal;


@Data
public class OrganizationPriorityDuration {
	private Organization organization;
	private Priority priority;
	private BigDecimal value;
	private SlaUnit unit;
}

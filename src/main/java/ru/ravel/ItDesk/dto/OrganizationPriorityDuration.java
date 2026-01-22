package ru.ravel.ItDesk.dto;

import lombok.Getter;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Priority;

@Getter
public class OrganizationPriorityDuration {
	private Organization organization;
	private Priority priority;
	private Float hours;
}

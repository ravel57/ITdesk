package ru.ravel.ItDesk.dto;

import lombok.Getter;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Priority;

@Getter
public class OrganizationPriority {
	Organization organization;
	Priority priority;
}

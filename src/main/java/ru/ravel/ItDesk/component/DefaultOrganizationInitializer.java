package ru.ravel.ItDesk.component;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.DefaultOrganization;
import ru.ravel.ItDesk.repository.DefaultOrganizationRepository;
import ru.ravel.ItDesk.repository.PriorityRepository;


@Component
@AllArgsConstructor
public class DefaultOrganizationInitializer {

	private final DefaultOrganizationRepository defaultOrganizationRepository;
	private final PriorityRepository priorityRepository;

	@PostConstruct
	public void init() {
		DefaultOrganization.initializeInstance(defaultOrganizationRepository, priorityRepository);
	}
}

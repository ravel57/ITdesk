package ru.ravel.ItDesk.component.initializer;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.model.DefaultOrganization;
import ru.ravel.ItDesk.repository.DefaultOrganizationRepository;


@Component
@AllArgsConstructor
public class DefaultOrganizationInitializer {

	private final DefaultOrganizationRepository defaultOrganizationRepository;

	@PostConstruct
	public void init() {
		DefaultOrganization.initializeInstance(defaultOrganizationRepository);
	}
}

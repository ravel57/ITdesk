package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.model.DefaultOrganization;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.DefaultOrganizationRepository;
import ru.ravel.ItDesk.repository.OrganizationRepository;
import ru.ravel.ItDesk.repository.PriorityRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Service
@RequiredArgsConstructor
public class OrganizationService {

	private final OrganizationRepository organizationRepository;
	private final PriorityRepository priorityRepository;
	private final DefaultOrganizationRepository defaultOrganizationRepository;
	private final ClientRepository clientRepository;


	public List<Organization> getOrganizations() {
		return organizationRepository.findAll().stream()
				.filter(organization -> !(organization instanceof DefaultOrganization))
				.sorted()
				.toList();
	}


	public Organization newOrganization(Organization organization) {
		return organizationRepository.save(organization);
	}


	public Organization updateOrganization(Organization organization) {
		return organizationRepository.save(organization);
	}


	public void deleteOrganization(Long organizationId) {
		clientRepository.findByOrganizationId(organizationId).forEach(client -> client.setOrganization(null));
		organizationRepository.deleteById(organizationId);
	}


	public Map<Organization, Map<Priority, Duration>> getSlaByPriority() {
		Map<Organization, Map<Priority, Duration>> slaByOrganization = new HashMap<>();
		organizationRepository.findAll().stream()
				.filter(organization -> !(organization instanceof DefaultOrganization))
				.forEach(org -> slaByOrganization.put(org, org.getSla()));
		slaByOrganization.put(DefaultOrganization.getInstance(), DefaultOrganization.getInstance().getSla());
		return slaByOrganization;
	}


	public void setSla(@NotNull OrganizationPriorityDuration organizationPriorityDuration) {
		Organization organization;
		if (organizationPriorityDuration.getOrganization() != null) {
			organization = organizationRepository.findById(organizationPriorityDuration.getOrganization().getId()).orElseThrow();
		} else {
			organization = DefaultOrganization.getInstance();
		}
		Duration duration = Duration.of(
				BigDecimal.valueOf(Objects.requireNonNullElse(organizationPriorityDuration.getHours(), 0.0F))
						.setScale(2, RoundingMode.HALF_UP)
						.multiply(BigDecimal.valueOf(60.0))
						.longValue(),
				ChronoUnit.MINUTES
		);
		Priority priority = organizationPriorityDuration.getPriority();
		organization.getSla().put(priority, duration);
		organizationRepository.save(organization);
	}

}
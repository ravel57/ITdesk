package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.model.*;
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


	public Map<Organization, Map<Priority, SlaValue>> getSlaByPriority() {
		Map<Organization, Map<Priority, SlaValue>> slaByOrganization = new HashMap<>();
		organizationRepository.findAll().stream()
				.filter(organization -> !(organization instanceof DefaultOrganization))
				.forEach(org -> slaByOrganization.put(org, org.getSla()));
		slaByOrganization.put(DefaultOrganization.getInstance(), DefaultOrganization.getInstance().getSla());
		return slaByOrganization;
	}


	public void setSla(@NotNull OrganizationPriorityDuration dto) {
		Organization organization;
		if (dto.getOrganization() != null) {
			organization = organizationRepository.findById(dto.getOrganization().getId()).orElseThrow();
		} else {
			organization = DefaultOrganization.getInstance();
		}
		Priority priority = dto.getPriority();
		BigDecimal value = dto.getValue() == null ? BigDecimal.ZERO : dto.getValue();
		SlaUnit unit = dto.getUnit() == null ? SlaUnit.HOURS : dto.getUnit();
		organization.getSla().put(priority, new SlaValue(value, unit));
		organizationRepository.save(organization);
	}

}
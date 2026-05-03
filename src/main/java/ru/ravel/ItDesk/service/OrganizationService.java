package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class OrganizationService {

	private final OrganizationRepository organizationRepository;
	private final PriorityRepository priorityRepository;
	private final DefaultOrganizationRepository defaultOrganizationRepository;
	private final ClientRepository clientRepository;
	private final OrganizationSlaRepository organizationSlaRepository;


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
		List<Organization> organizations = organizationRepository.findAll().stream()
				.filter(organization -> !(organization instanceof DefaultOrganization))
				.sorted()
				.toList();
		for (Organization organization : organizations) {
			slaByOrganization.put(
					organization,
					getSlaMapForOrganization(organization.getId())
			);
		}
		DefaultOrganization defaultOrganization = defaultOrganizationRepository.findAll()
				.stream()
				.findFirst()
				.orElseThrow();
		slaByOrganization.put(
				defaultOrganization,
				getSlaMapForOrganization(defaultOrganization.getId())
		);
		return slaByOrganization;
	}


	private Map<Priority, SlaValue> getSlaMapForOrganization(Long organizationId) {
		Map<Priority, SlaValue> result = new HashMap<>();
		organizationSlaRepository.findAllByOrganizationId(organizationId)
				.forEach(organizationSla -> result.put(
						organizationSla.getPriority(),
						new SlaValue(
								organizationSla.getValue(),
								organizationSla.getUnit()
						)
				));
		return result;
	}


	@Transactional
	public void setSla(@NotNull OrganizationPriorityDuration dto) {
		Organization organization;
		if (dto.getOrganization() != null) {
			organization = organizationRepository.findById(dto.getOrganization().getId()).orElseThrow();
		} else {
			organization = defaultOrganizationRepository.findAll()
					.stream()
					.findFirst()
					.orElseThrow();
		}
		Priority priority = priorityRepository.findById(dto.getPriority().getId()).orElseThrow();
		BigDecimal value = dto.getValue() == null ? BigDecimal.ZERO : dto.getValue();
		SlaUnit unit = dto.getUnit() == null ? SlaUnit.HOURS : dto.getUnit();
		int updated = organizationSlaRepository.updateSlaValue(
				organization.getId(),
				priority.getId(),
				value,
				unit
		);
		if (updated == 0) {
			OrganizationSla organizationSla = new OrganizationSla(
					organization,
					priority,
					value,
					unit
			);
			organizationSlaRepository.save(organizationSla);
		}
	}

}
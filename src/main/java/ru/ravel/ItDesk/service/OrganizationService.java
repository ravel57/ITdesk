package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.repository.OrganizationRepository;
import ru.ravel.ItDesk.repository.PriorityRepository;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrganizationService {

	private final OrganizationRepository organizationRepository;
	private final PriorityRepository priorityRepository;


	public List<Organization> getOrganizations() {
		return organizationRepository.findAll().stream().sorted().toList();
	}


	public Organization newOrganization(Organization organization) {
		return organizationRepository.save(organization);
	}


	public Organization updateOrganization(Organization organization) {
		return organizationRepository.save(organization);
	}


	public void deleteOrganization(Long organizationId) {
		organizationRepository.deleteById(organizationId);
	}


	public Map<Organization, Map<Priority, Duration>> getSlaByPriority() {
		Map<Organization, Map<Priority, Duration>> slaByOrganization = new HashMap<>();
		for (Organization organization : organizationRepository.findAll()) {
			for (Priority priority : priorityRepository.findAll()) {
				if (organization.getSlaByPriority() == null) {
					organization.setSlaByPriority(new HashMap<>());
				}
				Duration hours = organization.getSlaByPriority().get(priority.getId());
				Map<Priority, Duration> priorityDurationMap = slaByOrganization.get(organization);
				if (priorityDurationMap == null) {
					priorityDurationMap = new HashMap<>();
					priorityDurationMap.put(priority, hours);
					slaByOrganization.put(organization, priorityDurationMap);
				} else {
					priorityDurationMap.put(priority, hours);
				}
			}
		}
		return slaByOrganization;
	}


	public void postSlaByPriority(@NotNull OrganizationPriorityDuration organizationPriorityDuration) {
		Organization organization = organizationRepository.findById(organizationPriorityDuration.getOrganization().getId()).orElseThrow();
		Map<Long, Duration> slaByPriority = organization.getSlaByPriority();
		Duration duration = Duration.of(organizationPriorityDuration.getDuration(), ChronoUnit.HOURS);
		Long priorityId = organizationPriorityDuration.getPriority().getId();
		if (slaByPriority != null) {
			slaByPriority.put(priorityId, duration);
		} else {
			slaByPriority = Map.of(priorityId, duration);
		}
		organization.setSlaByPriority(slaByPriority);
		organizationRepository.save(organization);
	}

}

package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
	private final OrganizationVisitRepository organizationVisitRepository;


	public List<Organization> getOrganizations() {
		return organizationRepository.findAll().stream()
				.filter(organization -> !(organization instanceof DefaultOrganization))
				.sorted()
				.toList();
	}


	public Organization newOrganization(@NotNull Organization organization) {
		normalizeOrganizationSettings(organization);
		organization.setOrderNumber(getOrganizations().size() + 1);
		return organizationRepository.save(organization);
	}


	public Organization updateOrganization(Organization organization) {
		normalizeOrganizationSettings(organization);
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


	private void normalizeOrganizationSettings(Organization organization) {
		if (organization.getActive() == null) {
			organization.setActive(true);
		}
		if (organization.getOrderNumber() == null) {
			organization.setOrderNumber(0);
		}
		if (organization.getPriorityLevel() == null || organization.getPriorityLevel().isBlank()) {
			organization.setPriorityLevel("NORMAL");
		}
		if (organization.getUseVisitsLimit() == null) {
			organization.setUseVisitsLimit(false);
		}
		if (organization.getMonthlyVisitsLimit() == null || organization.getMonthlyVisitsLimit() < 0) {
			organization.setMonthlyVisitsLimit(0);
		}
		if (organization.getVisitsUsed() == null || organization.getVisitsUsed() < 0) {
			organization.setVisitsUsed(0);
		}
		if (organization.getVisitResetDay() == null) {
			organization.setVisitResetDay(1);
		}
		if (organization.getVisitResetDay() < 1) {
			organization.setVisitResetDay(1);
		}
		if (organization.getVisitResetDay() > 31) {
			organization.setVisitResetDay(31);
		}
		if (organization.getIncludedRemoteSupport() == null) {
			organization.setIncludedRemoteSupport(true);
		}
		if (organization.getSlaWorkCalendar() == null || organization.getSlaWorkCalendar().isBlank()) {
			organization.setSlaWorkCalendar("GENERAL_SETTINGS");
		}
		if (organization.getPauseSlaOnWaitingClient() == null) {
			organization.setPauseSlaOnWaitingClient(true);
		}
	}


	@Transactional
	public void setSla(@NotNull OrganizationPriorityDuration dto) {
		Organization organization;
		if (dto.getOrganization() != null) {
			organization = organizationRepository.findLockedById(dto.getOrganization().getId()).orElseThrow();
		} else {
			organization = defaultOrganizationRepository.findLocked().orElseThrow();
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
		if (updated > 0) {
			return;
		}
		OrganizationSla organizationSla = new OrganizationSla(
				organization,
				priority,
				value,
				unit
		);
		organizationSlaRepository.save(organizationSla);
	}


	@Transactional
	public List<Organization> resortOrganizations(@NotNull List<Organization> newOrderedOrganizations) {
		List<Organization> organizations = organizationRepository.findAll().stream()
				.filter(organization -> !(organization instanceof DefaultOrganization))
				.toList();

		for (Organization organization : organizations) {
			organization.setOrderNumber(newOrderedOrganizations.indexOf(organization));
		}

		organizationRepository.saveAll(organizations);
		return organizations.stream().sorted().toList();
	}

	public List<OrganizationVisit> getVisitHistory(Long organizationId) {
		return organizationVisitRepository.findAllByOrganizationIdOrderByVisitDateDescIdDesc(organizationId);
	}


	@Transactional
	public Map<String, Object> addVisit(Long organizationId, OrganizationVisit visit) {
		Organization organization = organizationRepository.findLockedById(organizationId).orElseThrow();

		normalizeOrganizationSettings(organization);

		boolean countedInPackage = visit.getCountedInPackage() == null || visit.getCountedInPackage();

		int currentVisitsUsed = organization.getVisitsUsed() == null ? 0 : organization.getVisitsUsed();
		int monthlyVisitsLimit = organization.getMonthlyVisitsLimit() == null ? 0 : organization.getMonthlyVisitsLimit();
		int visitsUsedAfter = countedInPackage ? currentVisitsUsed + 1 : currentVisitsUsed;

		organization.setVisitsUsed(visitsUsedAfter);

		visit.setOrganization(organization);

		if (visit.getVisitDate() == null) {
			visit.setVisitDate(LocalDateTime.now());
		}
		if (visit.getCreatedAt() == null) {
			visit.setCreatedAt(LocalDateTime.now());
		}
		if (visit.getType() == null || visit.getType().isBlank()) {
			visit.setType("Выезд");
		}
		visit.setCountedInPackage(countedInPackage);
		visit.setVisitsUsedAfter(visitsUsedAfter);
		visit.setMonthlyVisitsLimitSnapshot(monthlyVisitsLimit);
		visit.setOverLimit(countedInPackage && monthlyVisitsLimit > 0 && visitsUsedAfter > monthlyVisitsLimit);

		Organization savedOrganization = organizationRepository.save(organization);
		OrganizationVisit savedVisit = organizationVisitRepository.save(visit);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("organization", savedOrganization);
		result.put("visit", savedVisit);

		return result;
	}

}
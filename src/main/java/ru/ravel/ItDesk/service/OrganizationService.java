package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.repository.OrganizationRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
public class OrganizationService {

	private final OrganizationRepository organizationRepository;


	public List<Organization> getOrganizations() {
		return organizationRepository.findAll();
	}

	public Organization newOrganization(Organization organization) {
		return organizationRepository.save(organization);
	}
}

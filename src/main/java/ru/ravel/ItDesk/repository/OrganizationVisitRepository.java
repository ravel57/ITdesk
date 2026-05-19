package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.OrganizationVisit;

import java.util.List;

@Repository
public interface OrganizationVisitRepository extends JpaRepository<OrganizationVisit, Long> {

	List<OrganizationVisit> findAllByOrganizationIdOrderByVisitDateDescIdDesc(Long organizationId);

}
package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.OrganizationServiceAssignment;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrganizationServiceAssignmentRepository extends JpaRepository<OrganizationServiceAssignment, Long> {
	List<OrganizationServiceAssignment> findAllByOrganizationIdOrderByDefaultForOrganizationDescIdAsc(Long organizationId);
	List<OrganizationServiceAssignment> findAllByServiceIdOrderByOrganizationNameAsc(Long serviceId);
	List<OrganizationServiceAssignment> findAllByOrganizationIdAndActiveTrue(Long organizationId);
	Optional<OrganizationServiceAssignment> findFirstByOrganizationIdAndServiceIdAndActiveTrueOrderByIdDesc(Long organizationId, Long serviceId);
	Optional<OrganizationServiceAssignment> findFirstByOrganizationIdAndDefaultForOrganizationTrueAndActiveTrueOrderByIdDesc(Long organizationId);

	@Modifying(flushAutomatically = true)
	@Query("""
			update OrganizationServiceAssignment assignment
			set assignment.defaultForOrganization = false
			where assignment.organization.id = :organizationId
			  and assignment.active = true
			""")
	int clearActiveDefaultForOrganization(@Param("organizationId") Long organizationId);

	@Modifying(flushAutomatically = true)
	@Query("""
			update OrganizationServiceAssignment assignment
			set assignment.defaultForOrganization = false
			where assignment.organization.id = :organizationId
			  and assignment.active = true
			  and assignment.id <> :assignmentId
			""")
	int clearOtherActiveDefaultsForOrganization(
			@Param("organizationId") Long organizationId,
			@Param("assignmentId") Long assignmentId
	);

	boolean existsByServiceId(Long serviceId);
}

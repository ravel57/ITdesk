package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Organization;

import java.util.Optional;

@Repository
public interface OrganizationRepository extends JpaRepository<Organization, Long> {

	@EntityGraph(attributePaths = "sla")
	Optional<Organization> findWithSlaById(Long id);

}

package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.model.incident.IncidentStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentRepository extends JpaRepository<SupportIncident, Long> {
	List<SupportIncident> findAllByStatusNotIn(Collection<IncidentStatus> statuses);

	Optional<SupportIncident> findFirstByServicesIdAndStatusNotInOrderByIdDesc(Long serviceId, Collection<IncidentStatus> statuses);
	boolean existsByServicesId(Long serviceId);
}

package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.incident.IncidentTimelineEntry;

import java.util.List;

@Repository
public interface IncidentTimelineEntryRepository extends JpaRepository<IncidentTimelineEntry, Long> {
	List<IncidentTimelineEntry> findAllByIncidentIdOrderByCreatedAtAscIdAsc(Long incidentId);
}

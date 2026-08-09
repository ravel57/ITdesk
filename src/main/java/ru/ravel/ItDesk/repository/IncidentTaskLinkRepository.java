package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.incident.IncidentTaskLink;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentTaskLinkRepository extends JpaRepository<IncidentTaskLink, Long> {

	List<IncidentTaskLink> findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(Long incidentId);


	Optional<IncidentTaskLink> findFirstByIncidentIdAndTaskIdAndActiveTrue(Long incidentId, Long taskId);


	Optional<IncidentTaskLink> findFirstByTaskIdAndActiveTrue(Long taskId);


	boolean existsByTaskIdAndActiveTrue(Long taskId);


	@Query("""
			select l.incident.id as incidentId,
			       count(l.id) as linkedTasksCount,
			       sum(case when l.recoveryConfirmed = true then 1 else 0 end) as confirmedTasksCount
			from IncidentTaskLink l
			where l.active = true
			  and l.incident.id in :incidentIds
			group by l.incident.id
			""")
	List<IncidentTaskStats> summarizeActiveLinks(@Param("incidentIds") Collection<Long> incidentIds);


	interface IncidentTaskStats {
		Long getIncidentId();

		Long getLinkedTasksCount();

		Long getConfirmedTasksCount();
	}
}

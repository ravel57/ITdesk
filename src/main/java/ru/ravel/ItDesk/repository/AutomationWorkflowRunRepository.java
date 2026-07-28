package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.AutomationWorkflowRun;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;


public interface AutomationWorkflowRunRepository extends JpaRepository<AutomationWorkflowRun, Long> {
	List<AutomationWorkflowRun> findTop50ByTriggerIdOrderByCreatedAtDesc(Long triggerId);

	List<AutomationWorkflowRun> findTop200ByStatusAndWaitEventTypeOrderByCreatedAtAsc(
			AutomationWorkflowRunStatus status,
			ru.ravel.ItDesk.model.automatosation.TriggerType waitEventType
	);


	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update AutomationWorkflowRun r
			set r.status = :cancelledStatus,
				r.completedAt = :completedAt,
				r.updatedAt = :completedAt,
				r.lastError = :reason
			where r.triggerId = :triggerId
			  and r.status in :activeStatuses
			""")
	int cancelActiveRuns(
			@Param("triggerId") Long triggerId,
			@Param("activeStatuses") Collection<AutomationWorkflowRunStatus> activeStatuses,
			@Param("cancelledStatus") AutomationWorkflowRunStatus cancelledStatus,
			@Param("completedAt") Instant completedAt,
			@Param("reason") String reason
	);
}

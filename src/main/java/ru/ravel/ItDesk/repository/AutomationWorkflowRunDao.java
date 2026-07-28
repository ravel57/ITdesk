package ru.ravel.ItDesk.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;

import java.time.Instant;
import java.util.List;


@Repository
public class AutomationWorkflowRunDao {

	@PersistenceContext
	private EntityManager em;


	@Transactional
	public List<Long> fetchAndMarkProcessing(int limit) {
		em.createNativeQuery("""
				update automation_workflow_run
				set status = 'NEW',
					current_node_id = timeout_node_id,
					updated_at = now(),
					wait_kind = null,
					wait_event_type = null,
					correlation_key = null,
					resume_node_id = null,
					rejected_node_id = null,
					timeout_node_id = null,
					wait_expression = null
				where status in ('WAITING_EVENT', 'WAITING_APPROVAL')
				  and available_at <= now()
				  and timeout_node_id is not null
				""").executeUpdate();

		em.createNativeQuery("""
				update automation_workflow_run
				set status = 'NEW',
					retries = retries + 1,
					available_at = now(),
					updated_at = now(),
					last_error = 'Выполнение было прервано и автоматически возобновлено'
				where status = 'PROCESSING'
				  and updated_at < now() - interval '10 minutes'
				""").executeUpdate();

		@SuppressWarnings("unchecked")
		List<Long> ids = em.createNativeQuery("""
				select id
				from automation_workflow_run
				where status = 'NEW' and available_at <= now()
				order by available_at, created_at
				for update skip locked
				limit :limit
				""")
				.setParameter("limit", limit)
				.getResultList();

		if (ids.isEmpty()) {
			return ids;
		}

		em.createQuery("""
				update AutomationWorkflowRun r
				set r.status = :status, r.updatedAt = :now
				where r.id in :ids
				""")
				.setParameter("status", AutomationWorkflowRunStatus.PROCESSING)
				.setParameter("now", Instant.now())
				.setParameter("ids", ids)
				.executeUpdate();

		return ids;
	}
}

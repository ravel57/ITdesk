package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.time.Instant;
import java.util.List;


public interface AutomationOutboxRepository extends JpaRepository<Event, Long> {

	@Query(value = """
			select exists (
			    select 1
			    from event e
			    where e.trigger_type = :triggerType
			      and e.payload -> 'task' ->> 'id' = cast(:taskId as text)
			)
			""", nativeQuery = true)
	boolean existsByTriggerTypeAndTaskId(
			@Param("triggerType") String triggerType,
			@Param("taskId") Long taskId
	);


	@Query(value = """
			select exists (
			    select 1
			    from event e
			    where e.trigger_type = :triggerType
			      and e.payload -> 'client' ->> 'id' = cast(:clientId as text)
			)
			""", nativeQuery = true)
	boolean existsByTriggerTypeAndClientId(
			@Param("triggerType") String triggerType,
			@Param("clientId") Long clientId
	);


	List<Event> findAllByTriggerTypeAndCreatedAtBetween(
			TriggerType triggerType,
			Instant from,
			Instant to
	);


	@Query(value = """
		select *
		from event e
		where e.payload -> 'task' ->> 'id' = cast(:taskId as text)
		order by e.created_at desc
		""", nativeQuery = true)
	List<Event> findTaskHistoryEvents(@Param("taskId") Long taskId);


	@Query(value = """
		select
			e.created_at as createdAt,
			cast(e.payload -> 'task' ->> 'id' as bigint) as taskId
		from event e
		where e.trigger_type = :triggerType
		  and e.created_at >= :from
		  and e.created_at <= :to
		  and e.payload -> 'task' ->> 'id' is not null
		order by e.created_at
		""", nativeQuery = true)
	List<TaskReopenedAnalyticsRow> findTaskReopenedAnalyticsRows(
			@Param("triggerType") String triggerType,
			@Param("from") Instant from,
			@Param("to") Instant to
	);

	interface TaskReopenedAnalyticsRow {
		Instant getCreatedAt();

		Long getTaskId();
	}

}
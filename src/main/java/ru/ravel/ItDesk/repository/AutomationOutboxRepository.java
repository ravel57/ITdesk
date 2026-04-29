package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.Event;


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

}
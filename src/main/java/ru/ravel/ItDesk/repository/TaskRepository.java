package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

	@Query("""
			select t from Task t
			left join fetch t.sla s
			left join fetch s.pauses
			where t.id = :id
			""")
	Optional<Task> findByIdWithSla(Long id);


	@Query("""
			select t
			from Task t
			where t.deadline is not null
			  and t.deadline < :now
			  and coalesce(t.completed, false) = false
			""")
	List<Task> findOverdueNotCompleted(ZonedDateTime now);


	@Query("""
			select distinct t
			from Task t
			left join fetch t.sla s
			left join fetch s.pauses
			where coalesce(t.completed, false) = false
			  and t.sla is not null
			""")
	List<Task> findAllActiveWithSla();


	boolean existsByTypeId(Long typeId);


	List<Task> findAllByTypeId(Long typeId);


	Optional<Task> findBySlaId(Long slaId);


	@Query("""
			select
				t.id as id,
				t.createdAt as createdAt,
				t.closedAt as closedAt,
				t.deadline as deadline,
				coalesce(t.completed, false) as completed,
				t.type as type,
				t.priority as priority,
				t.executor as executor
			from Task t
			where (:hasTypeIds = false or t.type.id in :typeIds)
			  and (:hasPriorityIds = false or t.priority.id in :priorityIds)
			  and (:hasExecutorIds = false or t.executor.id in :executorIds)
			  and (
			      :hasTagIds = false
			      or exists (
			          select tag.id
			          from t.tags tag
			          where tag.id in :tagIds
			      )
			  )
			""")
	List<AnalyticsTaskRow> findAnalyticsTaskRows(
			@Param("hasTypeIds") boolean hasTypeIds,
			@Param("typeIds") Set<Long> typeIds,
			@Param("hasPriorityIds") boolean hasPriorityIds,
			@Param("priorityIds") Set<Long> priorityIds,
			@Param("hasExecutorIds") boolean hasExecutorIds,
			@Param("executorIds") Set<Long> executorIds,
			@Param("hasTagIds") boolean hasTagIds,
			@Param("tagIds") Set<Long> tagIds
	);


	@Query("""
			select
				t.id as taskId,
				tag as tag
			from Task t
			join t.tags tag
			where (:hasTypeIds = false or t.type.id in :typeIds)
			  and (:hasPriorityIds = false or t.priority.id in :priorityIds)
			  and (:hasExecutorIds = false or t.executor.id in :executorIds)
			  and (
			      :hasTagIds = false
			      or exists (
			          select filterTag.id
			          from t.tags filterTag
			          where filterTag.id in :tagIds
			      )
			  )
			""")
	List<AnalyticsTaskTagRow> findAnalyticsTaskTagRows(
			@Param("hasTypeIds") boolean hasTypeIds,
			@Param("typeIds") Set<Long> typeIds,
			@Param("hasPriorityIds") boolean hasPriorityIds,
			@Param("priorityIds") Set<Long> priorityIds,
			@Param("hasExecutorIds") boolean hasExecutorIds,
			@Param("executorIds") Set<Long> executorIds,
			@Param("hasTagIds") boolean hasTagIds,
			@Param("tagIds") Set<Long> tagIds
	);


	@Query("""
			select
				t.id as id,
				t.sla as sla,
				t.type as type,
				t.priority as priority,
				t.executor as executor
			from Task t
			where coalesce(t.completed, false) = false
			  and t.sla is not null
			  and (:hasTypeIds = false or t.type.id in :typeIds)
			  and (:hasPriorityIds = false or t.priority.id in :priorityIds)
			  and (:hasExecutorIds = false or t.executor.id in :executorIds)
			  and (
			      :hasTagIds = false
			      or exists (
			          select tag.id
			          from t.tags tag
			          where tag.id in :tagIds
			      )
			  )
			""")
	List<SlaAnalyticsRow> findSlaAnalyticsRows(
			@Param("hasTypeIds") boolean hasTypeIds,
			@Param("typeIds") Set<Long> typeIds,
			@Param("hasPriorityIds") boolean hasPriorityIds,
			@Param("priorityIds") Set<Long> priorityIds,
			@Param("hasExecutorIds") boolean hasExecutorIds,
			@Param("executorIds") Set<Long> executorIds,
			@Param("hasTagIds") boolean hasTagIds,
			@Param("tagIds") Set<Long> tagIds
	);


	interface AnalyticsTaskRow {
		Long getId();

		ZonedDateTime getCreatedAt();

		ZonedDateTime getClosedAt();

		ZonedDateTime getDeadline();

		Boolean getCompleted();

		Object getType();

		Object getPriority();

		User getExecutor();
	}


	interface AnalyticsTaskTagRow {
		Long getTaskId();

		Object getTag();
	}


	interface SlaAnalyticsRow {
		Long getId();

		Sla getSla();

		Object getType();

		Object getPriority();

		User getExecutor();
	}
}

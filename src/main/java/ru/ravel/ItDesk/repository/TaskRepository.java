package ru.ravel.ItDesk.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.OlaStatus;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;


@Repository
public interface TaskRepository extends JpaRepository<Task, Long> {

	@Modifying
	@Query(value = """
			insert into task_user_open(task_id, user_id, opened_at)
			values (:taskId, :userId, now())
			on conflict (task_id, user_id) do nothing
			""", nativeQuery = true)
	int markOpened(@Param("taskId") Long taskId, @Param("userId") Long userId);

	@Query("""
			select t from Task t
			left join fetch t.sla s
			left join fetch s.pauses
			where t.id = :id
			""")
	Optional<Task> findByIdWithSla(Long id);

	@Query("""
			select distinct t from Task t
			left join fetch t.sla
			where t.id in :ids
			""")
	List<Task> findAllByIdInWithSla(@Param("ids") Collection<Long> ids);


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


	@Query("""
			select t
			from Task t
			where coalesce(t.completed, false) = false
			  and t.firstResponseAt is null
			  and t.firstResponseDeadline is not null
			""")
	List<Task> findAllActiveAwaitingFirstResponse();


	boolean existsByTypeId(Long typeId);


	List<Task> findAllByTypeId(Long typeId);


	Optional<Task> findBySlaId(Long slaId);


	boolean existsBySupportLineId(Long supportLineId);


	List<Task> findAllBySupportLineId(Long supportLineId);


	@Query("""
			select t
			from Task t
			where t.supportLine.id = :supportLineId
			  and coalesce(t.completed, false) = false
			""")
	List<Task> findOpenBySupportLineId(@Param("supportLineId") Long supportLineId);


	@Query("""
			select t
			from Client c
			join c.tasks t
			where c.id = :clientId
			  and t.linkedMessageId = :linkedMessageId
			""")
	List<Task> findAllByClientIdAndLinkedMessageId(
			@Param("clientId") Long clientId,
			@Param("linkedMessageId") Long linkedMessageId
	);


	@Query("""
			select count(t)
			from Task t
			where t.executor.id = :executorId
			  and coalesce(t.completed, false) = false
			""")
	long countOpenByExecutorId(@Param("executorId") Long executorId);


	@Query(value = """
			select t.*
			from task t
			where t.client_id = :clientId
			  and coalesce(t.unread_ping_tasks_messages ->> cast(:userId as text), 'false') = 'true'
			order by coalesce(t.last_activity, t.created_at) desc nulls last, t.id desc
			""", nativeQuery = true)
	List<Task> findPingedTasksByClientIdAndUserId(
			@Param("clientId") Long clientId,
			@Param("userId") Long userId
	);


	@Query("""
			select t
			from Client c
			join c.tasks t
			where c.id = :clientId
			  and coalesce(t.completed, false) = false
			  and (:excludeTaskId is null or t.id <> :excludeTaskId)
			  and (:typeId is null or t.type.id = :typeId)
			  and (:createdAfter is null or t.createdAt >= :createdAfter)
			  and (:useExactTitle = false or lower(coalesce(t.name, '')) = lower(:title))
			  and (:useContainsTitle = false or lower(coalesce(t.name, '')) like lower(concat('%', :title, '%')))
			order by t.createdAt desc
			""")
	List<Task> findPotentialDuplicates(
			@Param("clientId") Long clientId,
			@Param("excludeTaskId") Long excludeTaskId,
			@Param("typeId") Long typeId,
			@Param("createdAfter") ZonedDateTime createdAfter,
			@Param("useExactTitle") boolean useExactTitle,
			@Param("useContainsTitle") boolean useContainsTitle,
			@Param("title") String title,
			Pageable pageable
	);


	@Query("""
			select
				t.id as id,
				t.createdAt as createdAt,
				t.closedAt as closedAt,
				t.deadline as deadline,
				coalesce(t.completed, false) as completed,
				taskType as type,
				priority as priority,
				taskService as service,
				executor as executor,
				supportLine as supportLine,
				t.enteredCurrentLineAt as enteredCurrentLineAt,
				t.olaDeadline as olaDeadline,
				t.olaWarningAt as olaWarningAt,
				t.olaStatus as olaStatus,
				t.linkedMessageId as linkedMessageId
			from Task t
			left join t.type taskType
			left join t.priority priority
			left join t.service taskService
			left join t.executor executor
			left join t.supportLine supportLine
			where (:hasTypeIds = false or taskType.id in :typeIds)
			  and (:hasPriorityIds = false or priority.id in :priorityIds)
			  and (:hasExecutorIds = false or executor.id in :executorIds)
			  and (:hasSupportLineIds = false or supportLine.id in :supportLineIds)
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
			@Param("hasSupportLineIds") boolean hasSupportLineIds,
			@Param("supportLineIds") Set<Long> supportLineIds,
			@Param("hasTagIds") boolean hasTagIds,
			@Param("tagIds") Set<Long> tagIds
	);


	@Query("""
			select
				t.id as taskId,
				accessUser.id as userId
			from Task t
			join t.accessUsers accessUser
			where t.id in :taskIds
			""")
	List<AnalyticsTaskAccessUserRow> findAnalyticsTaskAccessUserRows(@Param("taskIds") Set<Long> taskIds);


	@Query("""
			select
				t.id as taskId,
				tag as tag
			from Task t
			left join t.type taskType
			left join t.priority priority
			left join t.executor executor
			left join t.supportLine supportLine
			join t.tags tag
			where (:hasTypeIds = false or taskType.id in :typeIds)
			  and (:hasPriorityIds = false or priority.id in :priorityIds)
			  and (:hasExecutorIds = false or executor.id in :executorIds)
			  and (:hasSupportLineIds = false or supportLine.id in :supportLineIds)
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
			@Param("hasSupportLineIds") boolean hasSupportLineIds,
			@Param("supportLineIds") Set<Long> supportLineIds,
			@Param("hasTagIds") boolean hasTagIds,
			@Param("tagIds") Set<Long> tagIds
	);


	@Query("""
			select
				t.id as id,
				sla as sla,
				taskType as type,
				priority as priority,
				executor as executor,
				supportLine as supportLine
			from Task t
			join t.sla sla
			left join t.type taskType
			left join t.priority priority
			left join t.executor executor
			left join t.supportLine supportLine
			where coalesce(t.completed, false) = false
			  and (:hasTypeIds = false or taskType.id in :typeIds)
			  and (:hasPriorityIds = false or priority.id in :priorityIds)
			  and (:hasExecutorIds = false or executor.id in :executorIds)
			  and (:hasSupportLineIds = false or supportLine.id in :supportLineIds)
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
			@Param("hasSupportLineIds") boolean hasSupportLineIds,
			@Param("supportLineIds") Set<Long> supportLineIds,
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

		Object getService();

		User getExecutor();

		SupportLine getSupportLine();

		ZonedDateTime getEnteredCurrentLineAt();

		ZonedDateTime getOlaDeadline();

		ZonedDateTime getOlaWarningAt();

		OlaStatus getOlaStatus();

		Long getLinkedMessageId();
	}


	interface AnalyticsTaskAccessUserRow {
		Long getTaskId();

		Long getUserId();
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

		SupportLine getSupportLine();
	}

	boolean existsByServiceId(Long serviceId);

	List<Task> findAllByServiceId(Long serviceId);


	/** Заявка, в которую входит сообщение (message.task_id). */
	@Query("select t from Task t join t.messages m where m.id = :messageId")
	List<Task> findAllByMessageId(@Param("messageId") Long messageId);


	/** Открытые заявки клиента, созданные не раньше указанного момента, новые первыми. */
	@Query("""
			select t
			from Client c
			join c.tasks t
			where c.id = :clientId
			  and t.completed = false
			  and t.createdAt >= :since
			order by t.createdAt desc
			""")
	List<Task> findOpenByClientIdCreatedSince(
			@Param("clientId") Long clientId,
			@Param("since") ZonedDateTime since
	);


	/** Открытые заявки всех клиентов, созданные не раньше указанного момента, новые первыми. */
	@Query("""
			select t
			from Task t
			where coalesce(t.completed, false) = false
			  and t.createdAt >= :since
			order by t.createdAt desc
			""")
	List<Task> findOpenCreatedSince(@Param("since") ZonedDateTime since, Pageable pageable);
}

package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Task;

import java.util.List;
import java.util.Optional;
import ru.ravel.ItDesk.model.Message;


@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

	Optional<Client> findByTelegramIdAndTgBotId(Long telegramId, Long tgBotId);


	Client findByWhatsappRecipient(String whatsappRecipient);


	List<Client> findByOrganizationId(Long organizationId);


	List<Client> findAllByTgBotId(Long tgBotId);


	@Query("""
			select c
			from Client c
			join c.tasks t
			where t.id = :taskId
			""")
	Optional<Client> findByTaskId(@Param("taskId") Long taskId);


	Optional<Client> findFirstByEmail(String email);


	@Query("""
			select count(t)
			from Client c
			join c.tasks t
			where c.id = :clientId
			  and coalesce(t.completed, false) = false
			  and coalesce(t.frozen, false) = false
			""")
	Long countOpenTasksByClientId(@Param("clientId") Long clientId);


	@Query("""
			select count(t)
			from Client c
			join c.tasks t
			where c.id = :clientId
			  and coalesce(t.completed, false) = false
			  and coalesce(t.frozen, false) = false
			  and t.executor is null
			""")
	Long countOpenTasksWithoutAssigneeByClientId(@Param("clientId") Long clientId);


	@Query("""
			select count(t)
			from Client c
			join c.tasks t
			join t.priority p
			where c.id = :clientId
			  and coalesce(t.completed, false) = false
			  and coalesce(t.frozen, false) = false
			  and coalesce(p.critical, false) = true
			""")
	Long countOpenCriticalTasksByClientId(@Param("clientId") Long clientId);


	@Query(value = """
			select count(*)
			from task t
			where t.client_id = :clientId
			  and coalesce(t.completed, false) = false
			  and coalesce(t.frozen, false) = false
			  and coalesce(t.unread_ping_tasks_messages ->> cast(:userId as text), 'false') = 'true'
			""", nativeQuery = true)
	Long countOpenTaskPingsByClientIdAndUserId(@Param("clientId") Long clientId, @Param("userId") Long userId);


	@Query("""
			select t
			from Client c
			join c.tasks t
			where c.id = :clientId
			  and coalesce(t.completed, false) = false
			  and coalesce(t.frozen, false) = false
			  and t.sla is not null
			  and t.sla.startDate is not null
			  and t.sla.duration is not null
			order by t.sla.startDate asc
			""")
	List<Task> findFirstOpenSlaTaskByClientId(@Param("clientId") Long clientId, Pageable pageable);


	@Query(value = """
			select t.linked_message_id as message_id, t.id as task_id
			from task t
			where t.client_id = :clientId
			  and t.linked_message_id in (:messageIds)
			""", nativeQuery = true)
	List<Object[]> findLinkedTaskIdsByClientIdAndMessageIds(
			@Param("clientId") Long clientId,
			@Param("messageIds") List<Long> messageIds
	);


	@Query("""
			select c
			from Client c
			join c.messages m
			where m.fileUuid = :fileUuid
			  and coalesce(m.deleted, false) = false
			""")
	Optional<Client> findByClientMessageFileUuid(@Param("fileUuid") String fileUuid);


	@Query("""
			select c
			from Client c
			join c.tasks t
			join t.messages m
			where m.fileUuid = :fileUuid
			  and coalesce(m.deleted, false) = false
			""")
	Optional<Client> findByTaskMessageFileUuid(@Param("fileUuid") String fileUuid);


	@Query("""
		select
			c as client,
			m as message
		from Client c
		join c.messages m
		where m.fileUuid = :fileUuid
		  and coalesce(m.deleted, false) = false
		order by m.date desc, m.id desc
		""")
	List<FileAccessRow> findClientMessageFileAccessRowsByFileUuid(@Param("fileUuid") String fileUuid);


	@Query("""
		select
			c as client,
			m as message
		from Client c
		join c.tasks t
		join t.messages m
		where m.fileUuid = :fileUuid
		  and coalesce(m.deleted, false) = false
		order by m.date desc, m.id desc
		""")
	List<FileAccessRow> findTaskMessageFileAccessRowsByFileUuid(@Param("fileUuid") String fileUuid);


	interface FileAccessRow {
		Client getClient();

		Message getMessage();
	}

}
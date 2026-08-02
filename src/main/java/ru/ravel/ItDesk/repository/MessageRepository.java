package ru.ravel.ItDesk.repository;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.User;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

	Optional<Message> findByMessengerMessageId(Integer messengerMessageId);

	@Query(value = """
			select *
			from message m
			where m.client_id = :clientId
			order by m.date desc nulls last, m.id desc
			limit :limit
			offset :offset
			""", nativeQuery = true)
	List<Message> findClientMessagesPageFromEnd(
			@Param("clientId") Long clientId,
			@Param("limit") int limit,
			@Param("offset") int offset
	);

	@Query(value = """
			select count(*)
			from message m
			where m.client_id = :clientId
			  and (
			       m.date > (select lm.date from message lm where lm.id = :messageId)
			    or (
			         m.date = (select lm.date from message lm where lm.id = :messageId)
			     and m.id > :messageId
			    )
			  )
			""", nativeQuery = true)
	long countClientMessagesAfterMessage(
			@Param("clientId") Long clientId,
			@Param("messageId") Long messageId
	);


	@Transactional
	@Modifying(clearAutomatically = true)
	@Query(
			value = """
					update message
					set client_id = :clientId
					where id = :messageId
					""",
			nativeQuery = true
	)
	void attachMessageToClient(
			@Param("clientId") Long clientId,
			@Param("messageId") Long messageId
	);


	@Query("""
			select count(m) > 0
			from Client c
			join c.messages m
			where c.id = :clientId
			  and m.messengerMessageId = :messengerMessageId
			""")
	boolean existsByMessengerMessageIdAndClientId(
			@Param("messengerMessageId") Integer messengerMessageId,
			@Param("clientId") Long clientId
	);

	@Query("""
			select m
			from Client c
			join c.messages m
			where c.id = :clientId
			  and m.messengerMessageId = :messengerMessageId
			""")
	Optional<Message> findByMessengerMessageIdAndClientId(
			@Param("messengerMessageId") Integer messengerMessageId,
			@Param("clientId") Long clientId
	);


	@Query("""
			select
				c as client,
				m as message
			from Client c
			join c.messages m
			where coalesce(m.deleted, false) = false
			  and m.text is not null
			  and trim(m.text) <> ''
			order by c.id asc, m.date asc, m.id asc
			""")
	List<ClientMessageSearchRow> findClientMessagesForGlobalSearch();


	@Query("""
			select
				m.id as id,
				c.id as clientId,
				m.date as date,
				m.isSent as sent,
				m.isComment as commentFlag,
				m.deleted as deleted,
				m.answerRequired as answerRequired,
				sender as sender
			from Client c
			join c.messages m
			left join m.user sender
			where m.date is not null
			  and m.date >= :from
			  and m.date <= :to
			  and coalesce(m.deleted, false) = false
			order by c.id asc, m.date asc, m.id asc
			""")
	List<MessageAnalyticsRow> findClientMessageAnalyticsRowsBetween(
			@Param("from") ZonedDateTime from,
			@Param("to") ZonedDateTime to
	);


	@Query("""
			select
				m.id as id,
				c.id as clientId,
				m.date as date,
				m.isSent as sent,
				m.isComment as commentFlag,
				m.deleted as deleted,
				m.answerRequired as answerRequired,
				sender as sender
			from Client c
			join c.messages m
			left join m.user sender
			where m.date is not null
			  and m.date <= :to
			  and coalesce(m.deleted, false) = false
			order by c.id asc, m.date asc, m.id asc
			""")
	List<MessageAnalyticsRow> findClientMessageAnalyticsRowsUntil(
			@Param("to") ZonedDateTime to
	);


	interface MessageAnalyticsRow {
		Long getId();

		Long getClientId();

		ZonedDateTime getDate();

		Boolean getSent();

		Boolean getCommentFlag();

		Boolean getDeleted();

		AnswerRequired getAnswerRequired();

		User getSender();
	}


	interface ClientMessageSearchRow {
		Client getClient();

		Message getMessage();
	}
}

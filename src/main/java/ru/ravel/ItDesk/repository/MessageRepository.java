package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.model.Message;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

	Optional<Message> findByFileUuid(String fileUuid);

	Optional<Message> findByMessengerMessageId(Integer messengerMessageId);

//	long countByClientId(Long clientId);

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
				m.id as id,
				c.id as clientId,
				m.date as date,
				m.isSent as sent,
				m.isComment as commentFlag,
				m.deleted as deleted,
				m.answerRequired as answerRequired
			from Client c
			join c.messages m
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


	interface MessageAnalyticsRow {
		Long getId();

		Long getClientId();

		ZonedDateTime getDate();

		Boolean getSent();

		Boolean getCommentFlag();

		Boolean getDeleted();

		AnswerRequired getAnswerRequired();
	}
}

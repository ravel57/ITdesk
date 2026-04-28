package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Message;

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

}

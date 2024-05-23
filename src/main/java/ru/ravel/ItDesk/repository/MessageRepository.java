package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Message;

import java.util.Optional;


@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {
	Optional<Message> findByFileUuid(String fileUuid);
	Optional<Message> findByMessengerMessageId(Integer messengerMessageId);
}

package ru.ravel.ItDesk.reposetory;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {
}

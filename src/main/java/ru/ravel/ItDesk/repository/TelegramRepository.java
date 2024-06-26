package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.TgBot;

@Repository
public interface TelegramRepository extends JpaRepository<TgBot, Long> {
	TgBot findByToken(String token);
}

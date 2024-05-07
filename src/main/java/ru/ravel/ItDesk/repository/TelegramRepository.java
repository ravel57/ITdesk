package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.MyTelegramBot;

@Repository
public interface TelegramRepository extends JpaRepository<MyTelegramBot, Long> {
}

package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Client;


@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {
	Client findByTelegramId(Long telegramId);
}

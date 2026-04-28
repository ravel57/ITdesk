package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Client;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

	Optional<Client> findByTelegramIdAndTgBotId(Long telegramId, Long tgBotId);

	Client findByWhatsappRecipient(String whatsappRecipient);

	List<Client> findByOrganizationId(Long organizationId);

	List<Client> findAllByTgBotId(Long tgBotId);
}
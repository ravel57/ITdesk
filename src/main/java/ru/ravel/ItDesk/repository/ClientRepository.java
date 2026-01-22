package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Organization;

import java.util.List;


@Repository
public interface ClientRepository extends JpaRepository<Client, Long> {

	Client findByTelegramId(Long telegramId);

	Client findByWhatsappRecipient(String whatsappRecipient);

	List<Client> findByOrganizationId(Long organizationId);

	List<Client> findAllByTgBotId(Long tgBotId);
}
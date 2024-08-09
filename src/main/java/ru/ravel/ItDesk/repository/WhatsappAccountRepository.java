package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.WhatsappAccount;

public interface WhatsappAccountRepository extends JpaRepository<WhatsappAccount, Long> {
}
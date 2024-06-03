package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.EmailAccount;

@Repository
public interface EmailAccountRepository extends JpaRepository<EmailAccount, Long> {
}
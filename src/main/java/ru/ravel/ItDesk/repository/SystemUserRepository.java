package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.SystemUser;


@Repository
public interface SystemUserRepository extends JpaRepository<SystemUser, Long> {
}

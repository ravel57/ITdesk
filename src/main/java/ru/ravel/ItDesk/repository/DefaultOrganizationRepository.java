package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.DefaultOrganization;

public interface DefaultOrganizationRepository extends JpaRepository<DefaultOrganization, Long> {
}

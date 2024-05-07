package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Organization;

@Repository
public interface OrganizationRepository  extends JpaRepository<Organization, Long> {
}

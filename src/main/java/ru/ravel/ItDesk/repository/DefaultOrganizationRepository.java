package ru.ravel.ItDesk.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import ru.ravel.ItDesk.model.DefaultOrganization;

import java.util.Optional;

public interface DefaultOrganizationRepository extends JpaRepository<DefaultOrganization, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from DefaultOrganization d")
	Optional<DefaultOrganization> findLocked();

}
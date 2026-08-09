package ru.ravel.ItDesk.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.JwtSigningKeyRecord;

import java.util.List;
import java.util.Optional;

@Repository
public interface JwtSigningKeyRepository extends JpaRepository<JwtSigningKeyRecord, String> {

	Optional<JwtSigningKeyRecord> findFirstByActiveTrueOrderByCreatedAtDesc();

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select k from JwtSigningKeyRecord k where k.active = true order by k.createdAt desc")
	List<JwtSigningKeyRecord> findActiveForUpdate();

	List<JwtSigningKeyRecord> findAllByOrderByCreatedAtDesc();
}

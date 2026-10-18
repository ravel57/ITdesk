package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.MonitoringProblem;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;


@Repository
public interface MonitoringProblemRepository extends JpaRepository<MonitoringProblem, Long> {

	Optional<MonitoringProblem> findByIntegrationIdAndEventId(Long integrationId, String eventId);

	List<MonitoringProblem> findAllByServiceIdAndStatusOrderBySeverityDescStartedAtDesc(Long serviceId, MonitoringProblem.Status status);

	List<MonitoringProblem> findTop50ByIntegrationIdOrderByUpdatedAtDesc(Long integrationId);

	@Modifying
	@Query("delete from MonitoringProblem p where p.status = ru.ravel.ItDesk.model.MonitoringProblem.Status.RESOLVED and p.updatedAt < :before")
	int deleteResolvedBefore(ZonedDateTime before);
}

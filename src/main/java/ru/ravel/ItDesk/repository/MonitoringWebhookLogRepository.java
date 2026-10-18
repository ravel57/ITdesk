package ru.ravel.ItDesk.repository;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.MonitoringWebhookLog;

import java.time.ZonedDateTime;
import java.util.List;


@Repository
public interface MonitoringWebhookLogRepository extends JpaRepository<MonitoringWebhookLog, Long> {

	List<MonitoringWebhookLog> findAllByIntegrationIdOrderByReceivedAtDesc(Long integrationId, Pageable pageable);

	@Modifying
	@Query("delete from MonitoringWebhookLog l where l.receivedAt < :before")
	int deleteOlderThan(ZonedDateTime before);
}

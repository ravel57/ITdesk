package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.ZabbixIntegration;
import ru.ravel.ItDesk.model.ZabbixIntegrationMode;

import java.util.List;
import java.util.Optional;

@Repository
public interface ZabbixIntegrationRepository extends JpaRepository<ZabbixIntegration, Long> {
	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

	Optional<ZabbixIntegration> findByWebhookKey(String webhookKey);

	List<ZabbixIntegration> findAllByModeAndActiveTrue(ZabbixIntegrationMode mode);
}

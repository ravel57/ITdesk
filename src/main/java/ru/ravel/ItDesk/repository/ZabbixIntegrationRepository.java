package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.ZabbixIntegration;

@Repository
public interface ZabbixIntegrationRepository extends JpaRepository<ZabbixIntegration, Long> {
	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}

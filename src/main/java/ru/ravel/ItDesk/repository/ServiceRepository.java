package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.Service;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServiceRepository extends JpaRepository<Service, Long> {
	Optional<Service> findByCodeIgnoreCase(String code);
	Optional<Service> findByNameIgnoreCase(String name);
	boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);
	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
	List<Service> findAllByActiveTrueOrderByOrderNumberAscNameAsc();
	List<Service> findAllByZabbixEnabledTrueAndActiveTrue();
	long countByMonitoringStatus(ServiceMonitoringStatus status);
	boolean existsByZabbixIntegrationId(Long integrationId);
	boolean existsByDependentServicesId(Long serviceId);
}

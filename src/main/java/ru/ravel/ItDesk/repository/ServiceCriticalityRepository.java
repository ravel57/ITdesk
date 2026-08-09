package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.ServiceCriticality;

import java.util.Optional;

@Repository
public interface ServiceCriticalityRepository extends JpaRepository<ServiceCriticality, Long> {
	Optional<ServiceCriticality> findByDefaultSelectionTrue();
	Optional<ServiceCriticality> findByNameIgnoreCase(String name);
	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}

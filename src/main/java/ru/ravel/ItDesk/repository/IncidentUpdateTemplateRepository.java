package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.incident.IncidentUpdateTemplate;

import java.util.List;

@Repository
public interface IncidentUpdateTemplateRepository extends JpaRepository<IncidentUpdateTemplate, Long> {
	List<IncidentUpdateTemplate> findAllByOrderByOrderNumberAscIdAsc();

	boolean existsByKey(String key);

	boolean existsByTitleIgnoreCaseAndIdNot(String title, Long id);
}

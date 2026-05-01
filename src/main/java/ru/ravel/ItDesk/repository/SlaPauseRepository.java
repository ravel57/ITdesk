package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.SlaPause;

import java.util.List;
import java.util.Optional;


public interface SlaPauseRepository extends JpaRepository<SlaPause, Long> {

	boolean existsBySlaIdAndEndedAtIsNull(Long slaId);

	Optional<SlaPause> findFirstBySlaIdAndEndedAtIsNull(Long slaId);

	List<SlaPause> findAllBySlaId(Long slaId);
}
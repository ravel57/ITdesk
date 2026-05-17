package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.SlaPause;

import java.util.List;
import java.util.Optional;


public interface SlaPauseRepository extends JpaRepository<SlaPause, Long> {

	boolean existsBySlaIdAndEndedAtIsNull(Long slaId);

	Optional<SlaPause> findFirstBySlaIdAndEndedAtIsNull(Long slaId);

	boolean existsBySlaIdAndReasonAndEndedAtIsNull(Long slaId, String reason);

	List<SlaPause> findAllBySlaId(Long slaId);

	Optional<SlaPause> findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(Long slaId);

	Optional<SlaPause> findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(Long slaId, String reason);

	List<SlaPause> findAllBySlaIdAndEndedAtIsNull(Long slaId);

	List<SlaPause> findAllBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(Long slaId,String reason);

	List<SlaPause> findAllBySlaIdAndReasonAndEndedAtIsNull(Long slaId, String reason);

}
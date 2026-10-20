package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.SlaPause;

import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface SlaPauseRepository extends JpaRepository<SlaPause, Long> {

	boolean existsBySlaIdAndEndedAtIsNull(Long slaId);

	Optional<SlaPause> findFirstBySlaIdAndEndedAtIsNull(Long slaId);

	boolean existsBySlaIdAndReasonAndEndedAtIsNull(Long slaId, String reason);

	List<SlaPause> findAllBySlaId(Long slaId);

	@Query("select p from SlaPause p join fetch p.sla where p.sla.id in :slaIds")
	List<SlaPause> findAllBySlaIdIn(@Param("slaIds") Collection<Long> slaIds);

	Optional<SlaPause> findFirstBySlaIdAndEndedAtIsNullOrderByStartedAtDesc(Long slaId);

	Optional<SlaPause> findFirstBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(Long slaId, String reason);

	List<SlaPause> findAllBySlaIdAndEndedAtIsNull(Long slaId);

	List<SlaPause> findAllBySlaIdAndReasonAndEndedAtIsNullOrderByStartedAtDesc(Long slaId,String reason);

	List<SlaPause> findAllBySlaIdAndReasonAndEndedAtIsNull(Long slaId, String reason);

}
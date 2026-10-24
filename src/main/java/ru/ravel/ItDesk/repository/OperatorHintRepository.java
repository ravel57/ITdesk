package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import ru.ravel.ItDesk.model.OperatorHint;

import java.time.ZonedDateTime;
import java.util.List;

@Repository
public interface OperatorHintRepository extends JpaRepository<OperatorHint, Long> {

	List<OperatorHint> findAllByClientIdOrderByIdAsc(Long clientId);

	/**
	 * По правилу и источнику: id правила, название правила (последнее), ключ источника, название источника,
	 * число подсказок, «помогло», «не помогло», средняя уверенность Jev. ruleId = null — все правила.
	 */
	@Query("""
			select h.ruleId, max(h.ruleName), h.source, max(h.sourceLabel), count(h),
			       sum(case when h.helpful = true then 1 else 0 end),
			       sum(case when h.helpful = false then 1 else 0 end), avg(h.probability)
			from OperatorHint h
			where h.createdAt >= :since and (:ruleId is null or h.ruleId = :ruleId)
			group by h.ruleId, h.source
			order by h.ruleId, h.source
			""")
	List<Object[]> statsSince(@Param("since") ZonedDateTime since, @Param("ruleId") Long ruleId);

}

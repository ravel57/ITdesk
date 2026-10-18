package ru.ravel.ItDesk.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.jev.JevDecision;

import java.time.ZonedDateTime;
import java.util.List;

public interface JevDecisionRepository extends JpaRepository<JevDecision, Long> {

	@Query("""
			select d from JevDecision d
			where d.createdAt >= :since
			  and (:action is null or d.action = :action)
			  and (:question is null or d.question = :question)
			  and (:outcome is null or d.outcome = :outcome)
			order by d.createdAt desc, d.id desc
			""")
	Page<JevDecision> search(
			@Param("since") ZonedDateTime since,
			@Param("action") String action,
			@Param("question") String question,
			@Param("outcome") String outcome,
			Pageable pageable
	);

	/**
	 * Сводка по действию и вопросу: всего, средняя уверенность, принято, отклонено, «ни один»,
	 * решений у порога (|уверенность − порог| ≤ 0.1).
	 */
	@Query("""
			select d.action, d.question, count(d), avg(d.confidence),
			       sum(case when d.outcome = 'ACCEPTED' then 1 else 0 end),
			       sum(case when d.outcome = 'REJECTED' then 1 else 0 end),
			       sum(case when d.outcome = 'NONE' then 1 else 0 end),
			       sum(case when d.threshold is not null and abs(d.confidence - d.threshold) <= 0.1 then 1 else 0 end),
			       max(d.threshold)
			from JevDecision d
			where d.createdAt >= :since
			group by d.action, d.question
			order by d.action, d.question
			""")
	List<Object[]> stats(@Param("since") ZonedDateTime since);

	@Modifying
	@Query("delete from JevDecision d where d.createdAt < :before")
	int deleteOlderThan(@Param("before") ZonedDateTime before);
}

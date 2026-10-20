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
			  and (:ruleId is null or d.ruleId = :ruleId)
			  and (:nodeId is null or d.nodeId = :nodeId)
			order by d.createdAt desc, d.id desc
			""")
	Page<JevDecision> search(
			@Param("since") ZonedDateTime since,
			@Param("action") String action,
			@Param("question") String question,
			@Param("outcome") String outcome,
			@Param("ruleId") Long ruleId,
			@Param("nodeId") String nodeId,
			Pageable pageable
	);

	/** Aggregate per rule and graph step. A graph may have several gates; no synthetic global threshold. */
    @Query("""
            select d.ruleId, d.ruleName, d.nodeId, d.nodeLabel, d.action, d.question, d.questionTitle, d.answerType,
                   count(d), avg(d.confidence),
                   sum(case when d.outcome in ('ANSWERED','ACCEPTED','REJECTED','NONE','UNDECIDED') then 1 else 0 end),
                   sum(case when d.outcome in ('FAILED','INVALID') then 1 else 0 end),
                   sum(case when d.outcome = 'SKIPPED' then 1 else 0 end),
                   sum(case when d.outcome = 'PENDING' then 1 else 0 end)
            from JevDecision d where d.createdAt >= :since
            group by d.ruleId, d.ruleName, d.nodeId, d.nodeLabel, d.action, d.question, d.questionTitle, d.answerType
            order by d.ruleName, d.nodeLabel, d.action, d.question
            """)
    List<Object[]> stats(@Param("since") ZonedDateTime since);

	@Modifying
	@Query("delete from JevDecision d where d.createdAt < :before")
	int deleteOlderThan(@Param("before") ZonedDateTime before);
}

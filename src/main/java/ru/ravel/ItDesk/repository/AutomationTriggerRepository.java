package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.util.List;
import java.time.ZonedDateTime;


public interface AutomationTriggerRepository extends JpaRepository<AutomationTrigger, Long> {

	@Query("""
			    select t
			    from AutomationTrigger t
			    where t.triggerType = :triggerType and t.automationRuleStatus = :automationRuleStatus
			    order by t.orderNumber asc
			""")
	List<AutomationTrigger> findEnabledByTriggerTypeOrdered(
			@Param("triggerType") TriggerType triggerType,
			@Param("automationRuleStatus") AutomationRuleStatus automationRuleStatus
	);


	@Modifying(flushAutomatically = true)
	@Query("""
			update AutomationTrigger t
			set t.matchCount = coalesce(t.matchCount, 0) + 1,
				t.lastMatchedAt = :matchedAt
			where t.id = :id
			""")
	void recordMatch(@Param("id") Long id, @Param("matchedAt") ZonedDateTime matchedAt);


	@Modifying(flushAutomatically = true)
	@Query("""
			update AutomationTrigger t
			set t.failureCount = coalesce(t.failureCount, 0) + 1,
				t.lastFailedAt = :failedAt,
				t.lastError = :lastError
			where t.id = :id
			""")
	void recordFailure(
			@Param("id") Long id,
			@Param("failedAt") ZonedDateTime failedAt,
			@Param("lastError") String lastError
	);

}

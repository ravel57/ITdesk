package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;

import java.util.List;


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

}

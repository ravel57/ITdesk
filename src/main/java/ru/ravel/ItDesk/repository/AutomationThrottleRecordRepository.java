package ru.ravel.ItDesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.ravel.ItDesk.model.AutomationThrottleRecord;

import java.util.Optional;


public interface AutomationThrottleRecordRepository extends JpaRepository<AutomationThrottleRecord, Long> {
	Optional<AutomationThrottleRecord> findByTriggerIdAndNodeIdAndScopeKey(Long triggerId, String nodeId, String scopeKey);
}

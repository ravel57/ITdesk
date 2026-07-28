package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.AutomationWorkflowRun;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;
import ru.ravel.ItDesk.repository.AutomationWorkflowRunRepository;

import java.time.Instant;
import java.time.ZonedDateTime;


@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationWorkflowRunProcessor {

	private static final int MAX_RETRIES = 5;

	private final AutomationWorkflowRunRepository workflowRunRepository;
	private final AutomationTriggerRepository triggerRepository;
	private final AutomationWorkflowEngine workflowEngine;


	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processOneTx(Long runId) {
		AutomationWorkflowRun run = workflowRunRepository.findById(runId).orElseThrow();
		AutomationTrigger trigger = triggerRepository.findById(run.getTriggerId()).orElse(null);
		if (trigger == null || trigger.getAutomationRuleStatus() != AutomationRuleStatus.ENABLED) {
			run.setStatus(AutomationWorkflowRunStatus.CANCELLED);
			run.setCompletedAt(Instant.now());
			run.setLastError(trigger == null ? "Сценарий удалён" : "Сценарий отключён");
			workflowRunRepository.save(run);
			return;
		}

		try {
			workflowEngine.resume(trigger, run);
			run.setStatus(AutomationWorkflowRunStatus.DONE);
			run.setCompletedAt(Instant.now());
			run.setLastError(null);
			workflowRunRepository.save(run);
		} catch (Exception e) {
			int retries = run.getRetries() == null ? 0 : run.getRetries();
			run.setRetries(retries + 1);
			run.setLastError(trimError(e));
			if (retries + 1 >= MAX_RETRIES) {
				run.setStatus(AutomationWorkflowRunStatus.FAILED);
				run.setCompletedAt(Instant.now());
				triggerRepository.recordFailure(trigger.getId(), ZonedDateTime.now(), trimError(e));
			} else {
				long backoffSeconds = Math.min(300L, 1L << Math.min(retries + 1, 8));
				run.setStatus(AutomationWorkflowRunStatus.NEW);
				run.setAvailableAt(Instant.now().plusSeconds(backoffSeconds));
			}
			workflowRunRepository.save(run);
			log.warn(
					"Delayed automation run failed, runId={}, attempt={}, status={}, error={}",
					run.getId(),
					run.getRetries(),
					run.getStatus(),
					run.getLastError()
			);
		}
	}


	private String trimError(Throwable error) {
		String message = error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "" : error.getMessage());
		return message.length() > 1900 ? message.substring(0, 1900) : message;
	}
}

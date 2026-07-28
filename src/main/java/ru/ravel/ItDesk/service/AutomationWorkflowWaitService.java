package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.AutomationWorkflowRun;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;
import ru.ravel.ItDesk.repository.AutomationWorkflowRunRepository;

import java.time.Instant;
import java.util.List;


@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationWorkflowWaitService {

	private final AutomationWorkflowRunRepository repository;
	private final AutomationWorkflowValueService valueService;
	private final AutomationScriptRuntime scriptRuntime;


	@Transactional
	public int resumeForEvent(Event event) {
		if (event == null || event.getTriggerType() == null || event.getPayload() == null) return 0;
		List<AutomationWorkflowRun> candidates = repository.findTop200ByStatusAndWaitEventTypeOrderByCreatedAtAsc(
				AutomationWorkflowRunStatus.WAITING_EVENT,
				event.getTriggerType()
		);
		int resumed = 0;
		for (AutomationWorkflowRun run : candidates) {
			if (!matchesCorrelation(run.getCorrelationKey(), event)) continue;
			if (run.getWaitExpression() != null && !run.getWaitExpression().isBlank()) {
				try {
					if (!scriptRuntime.evaluateExpression(run.getWaitExpression(), event.getPayload())) continue;
				} catch (Exception e) {
					log.warn("Wait expression failed, runId={}, expression={}", run.getId(), run.getWaitExpression(), e);
					continue;
				}
			}
			run.setEventPayload(valueService.mergeRuntimeState(run.getEventPayload(), event.getPayload()));
			run.setSourceEventId(event.getId());
			run.setCurrentNodeId(run.getResumeNodeId());
			run.setStatus(AutomationWorkflowRunStatus.NEW);
			run.setAvailableAt(Instant.now());
			run.setActorUserId(event.getActorUserId());
			run.setActorUsername(event.getActorUsername());
			run.setActorDisplayName(event.getActorDisplayName());
			run.setActorType(event.getActorType());
			clearWaitFields(run);
			repository.save(run);
			resumed++;
		}
		return resumed;
	}


	private boolean matchesCorrelation(String correlationKey, Event event) {
		if (correlationKey == null || correlationKey.isBlank() || "global".equals(correlationKey)) return true;
		if (correlationKey.startsWith("task:")) {
			return correlationKey.equals(valueService.correlationKey(event.getPayload(), "TASK", null));
		}
		if (correlationKey.startsWith("client:")) {
			return correlationKey.equals(valueService.correlationKey(event.getPayload(), "CLIENT", null));
		}
		return correlationKey.equals(valueService.correlationKey(event.getPayload(), "CUSTOM", correlationKey.substring(correlationKey.indexOf(':') + 1)));
	}


	public void clearWaitFields(AutomationWorkflowRun run) {
		run.setWaitKind(null);
		run.setWaitEventType(null);
		run.setCorrelationKey(null);
		run.setResumeNodeId(null);
		run.setRejectedNodeId(null);
		run.setTimeoutNodeId(null);
		run.setWaitExpression(null);
	}
}

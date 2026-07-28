package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.EventStatus;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;

import java.time.Instant;
import java.time.ZonedDateTime;


@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationItemProcessor {

	private final AutomationOutboxRepository outboxRepository;
	private final AutomationScriptRuntime scriptRuntime;
	private final AutomationTriggerRepository automationTriggerRepository;
	private final AutomationWorkflowEngine workflowEngine;
	private final AutomationPayloadService payloadService;
	private final AutomationWorkflowWaitService workflowWaitService;


	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void processOneTx(Long id) {
		Event event = outboxRepository.findById(id).orElseThrow();
		try {
			handleEvent(event);
			event.setStatus(EventStatus.DONE);
			event.setLastError(null);
			outboxRepository.save(event);
		} catch (Exception ex) {
			int retries = event.getRetries();
			long backoffSec = Math.min(60, 1L << Math.min(retries, 10));
			event.setRetries(retries + 1);
			event.setStatus(EventStatus.NEW);
			event.setAvailableAt(Instant.now().plusSeconds(backoffSec));
			event.setLastError(trimError(ex));
			outboxRepository.save(event);
			log.warn(
					"Automation event processing failed, eventId={}, retry={}, error={}",
					event.getId(),
					event.getRetries(),
					event.getLastError()
			);
		}
	}


	public void handleEvent(Event event) {
		if (event == null || event.getPayload() == null) {
			return;
		}
		Event executionEvent = Event.builder()
				.id(event.getId())
				.triggerType(event.getTriggerType())
				.payload(event.getPayload().deepCopy())
				.actorUserId(event.getActorUserId())
				.actorUsername(event.getActorUsername())
				.actorDisplayName(event.getActorDisplayName())
				.actorType(event.getActorType())
				.build();
		payloadService.enrich(executionEvent);
		workflowWaitService.resumeForEvent(executionEvent);

		for (var trigger : automationTriggerRepository.findEnabledByTriggerTypeOrdered(
				executionEvent.getTriggerType(),
				AutomationRuleStatus.ENABLED
		)) {
			try {
				boolean executed;
				if (trigger.getWorkflowDefinition() != null && !trigger.getWorkflowDefinition().isBlank()) {
					AutomationWorkflowEngine.ExecutionResult result = workflowEngine.execute(trigger, executionEvent);
					executed = result.executed() || result.scheduled();
				} else {
					boolean matched = scriptRuntime.evaluateExpression(trigger.getExpression(), executionEvent.getPayload());
					String selectedAction = matched ? trigger.getAction() : trigger.getElseAction();
					if (selectedAction == null || selectedAction.isBlank()) {
						continue;
					}
					scriptRuntime.executeActions(selectedAction, new AutomationExecutionContext(trigger, executionEvent));
					executed = true;
				}

				if (executed) {
					automationTriggerRepository.recordMatch(trigger.getId(), ZonedDateTime.now());
					if (Boolean.TRUE.equals(trigger.getStopProcessing())) {
						break;
					}
				}
			} catch (Exception error) {
				String lastError = trimError(error);
				automationTriggerRepository.recordFailure(trigger.getId(), ZonedDateTime.now(), lastError);
				log.warn(
						"Automation trigger failed, triggerId={}, eventId={}, error={}",
						trigger.getId(),
						event.getId(),
						lastError
				);
			}
		}
	}


	private String trimError(Throwable error) {
		String message = error.getClass().getSimpleName() + ": " + (error.getMessage() == null ? "" : error.getMessage());
		return message.length() > 1900 ? message.substring(0, 1900) : message;
	}
}

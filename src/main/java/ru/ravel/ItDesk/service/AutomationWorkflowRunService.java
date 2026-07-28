package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationApprovalDecisionRequest;
import ru.ravel.ItDesk.model.AutomationWorkflowRun;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;
import ru.ravel.ItDesk.repository.AutomationWorkflowRunRepository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
public class AutomationWorkflowRunService {

	private final AutomationWorkflowRunRepository repository;
	private final AutomationWorkflowWaitService waitService;
	private final UserService userService;


	@Transactional(readOnly = true)
	public List<Map<String, Object>> recent(Long triggerId) {
		if (triggerId == null) {
			throw new IllegalArgumentException("triggerId is required");
		}
		return repository.findTop50ByTriggerIdOrderByCreatedAtDesc(triggerId).stream()
				.map(this::toDto)
				.toList();
	}


	@Transactional
	public Map<String, Object> retry(Long runId) {
		AutomationWorkflowRun run = repository.findById(runId).orElseThrow();
		if (run.getStatus() == AutomationWorkflowRunStatus.PROCESSING) {
			throw new IllegalStateException("Выполнение уже запущено");
		}
		if (run.getStatus() == AutomationWorkflowRunStatus.DONE) {
			throw new IllegalStateException("Завершённое продолжение нельзя запустить повторно");
		}
		run.setStatus(AutomationWorkflowRunStatus.NEW);
		run.setAvailableAt(Instant.now());
		run.setCompletedAt(null);
		run.setLastError(null);
		waitService.clearWaitFields(run);
		return toDto(repository.save(run));
	}


	@Transactional
	public Map<String, Object> cancel(Long runId) {
		AutomationWorkflowRun run = repository.findById(runId).orElseThrow();
		if (run.getStatus() == AutomationWorkflowRunStatus.PROCESSING) {
			throw new IllegalStateException("Уже выполняющееся продолжение нельзя остановить безопасно");
		}
		if (run.getStatus() == AutomationWorkflowRunStatus.DONE
				|| run.getStatus() == AutomationWorkflowRunStatus.FAILED
				|| run.getStatus() == AutomationWorkflowRunStatus.CANCELLED) {
			return toDto(run);
		}
		run.setStatus(AutomationWorkflowRunStatus.CANCELLED);
		run.setCompletedAt(Instant.now());
		run.setLastError("Остановлено администратором");
		return toDto(repository.save(run));
	}


	@Transactional
	public Map<String, Object> approve(Long runId, AutomationApprovalDecisionRequest request) {
		return decide(runId, true, request == null ? null : request.getComment());
	}


	@Transactional
	public Map<String, Object> reject(Long runId, AutomationApprovalDecisionRequest request) {
		return decide(runId, false, request == null ? null : request.getComment());
	}


	private Map<String, Object> decide(Long runId, boolean approved, String comment) {
		AutomationWorkflowRun run = repository.findById(runId).orElseThrow();
		if (run.getStatus() != AutomationWorkflowRunStatus.WAITING_APPROVAL) {
			throw new IllegalStateException("Запуск не ожидает ручного решения");
		}
		if (run.getApproverUserId() != null) {
			Long currentUserId = userService.getCurrentUser().getId();
			if (!run.getApproverUserId().equals(currentUserId)) {
				throw new AccessDeniedException("Решение назначено другому пользователю");
			}
		}
		String nextNodeId = approved ? run.getResumeNodeId() : run.getRejectedNodeId();
		if (nextNodeId == null || nextNodeId.isBlank()) {
			throw new IllegalStateException(approved ? "Не настроена ветка одобрения" : "Не настроена ветка отклонения");
		}
		run.setCurrentNodeId(nextNodeId);
		run.setStatus(AutomationWorkflowRunStatus.NEW);
		run.setAvailableAt(Instant.now());
		run.setDecisionComment(comment);
		run.setCompletedAt(null);
		run.setLastError(null);
		waitService.clearWaitFields(run);
		return toDto(repository.save(run));
	}


	private Map<String, Object> toDto(AutomationWorkflowRun run) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", run.getId());
		result.put("triggerId", run.getTriggerId());
		result.put("sourceEventId", run.getSourceEventId());
		result.put("triggerType", run.getTriggerType());
		result.put("currentNodeId", run.getCurrentNodeId());
		result.put("status", run.getStatus());
		result.put("stepCount", run.getStepCount());
		result.put("retries", run.getRetries());
		result.put("availableAt", run.getAvailableAt());
		result.put("createdAt", run.getCreatedAt());
		result.put("updatedAt", run.getUpdatedAt());
		result.put("completedAt", run.getCompletedAt());
		result.put("lastError", run.getLastError());
		result.put("waitKind", run.getWaitKind());
		result.put("waitEventType", run.getWaitEventType());
		result.put("correlationKey", run.getCorrelationKey());
		result.put("approvalTitle", run.getApprovalTitle());
		result.put("approvalMessage", run.getApprovalMessage());
		result.put("approverUserId", run.getApproverUserId());
		result.put("decisionComment", run.getDecisionComment());
		return result;
	}
}

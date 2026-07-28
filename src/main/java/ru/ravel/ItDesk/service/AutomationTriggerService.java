package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationTriggerDto;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.automatosation.AutomationRuleStatus;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;
import ru.ravel.ItDesk.repository.AutomationWorkflowRunRepository;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;


@Service
@RequiredArgsConstructor
public class AutomationTriggerService {

	private final AutomationTriggerRepository repository;
	private final AutomationWorkflowRunRepository workflowRunRepository;
	private final AutomationWorkflowEngine workflowEngine;


	@Transactional(readOnly = true)
	public List<AutomationTriggerDto> list() {
		return repository.findAll().stream().sorted().map(this::toDto).toList();
	}


	@Transactional
	public AutomationTriggerDto create(AutomationTriggerDto dto) {
		validate(dto);
		int nextOrder = repository.findAll().size();
		String normalizedWorkflow = normalizeWorkflow(dto.getWorkflowDefinition());
		TriggerType triggerType = resolveTriggerType(dto, normalizedWorkflow);
		AutomationTrigger entity = AutomationTrigger.builder()
				.name(dto.getName().trim())
				.description(dto.getDescription())
				.triggerType(triggerType)
				.expression(normalizeLegacyExpression(dto, normalizedWorkflow))
				.action(normalizeLegacyAction(dto, normalizedWorkflow))
				.elseAction(normalizeOptionalAction(dto.getElseAction()))
				.workflowDefinition(normalizedWorkflow)
				.workflowVersion(dto.getWorkflowVersion() == null ? 1 : dto.getWorkflowVersion())
				.automationRuleStatus(resolveStatus(dto.getAutomationRuleStatus()))
				.stopProcessing(Boolean.TRUE.equals(dto.getStopProcessing()))
				.orderNumber(nextOrder)
				.build();
		return toDto(repository.save(entity));
	}


	@Transactional
	public AutomationTriggerDto update(AutomationTriggerDto dto) {
		if (dto.getId() == null) {
			throw new IllegalArgumentException("id is required");
		}
		validate(dto);
		AutomationTrigger entity = repository.findById(dto.getId()).orElseThrow();
		String normalizedWorkflow = normalizeWorkflow(dto.getWorkflowDefinition());
		TriggerType triggerType = resolveTriggerType(dto, normalizedWorkflow);
		entity.setName(dto.getName().trim());
		entity.setDescription(dto.getDescription());
		entity.setTriggerType(triggerType);
		entity.setExpression(normalizeLegacyExpression(dto, normalizedWorkflow));
		entity.setAction(normalizeLegacyAction(dto, normalizedWorkflow));
		entity.setElseAction(normalizeOptionalAction(dto.getElseAction()));
		entity.setWorkflowDefinition(normalizedWorkflow);
		entity.setWorkflowVersion(dto.getWorkflowVersion() == null ? 1 : dto.getWorkflowVersion());
		entity.setAutomationRuleStatus(resolveStatus(dto.getAutomationRuleStatus()));
		entity.setStopProcessing(Boolean.TRUE.equals(dto.getStopProcessing()));
		if (dto.getOrderNumber() != null) {
			entity.setOrderNumber(dto.getOrderNumber());
		}
		return toDto(repository.save(entity));
	}


	@Transactional
	public void delete(Long id) {
		workflowRunRepository.cancelActiveRuns(
				id,
				EnumSet.of(
						AutomationWorkflowRunStatus.NEW,
						AutomationWorkflowRunStatus.PROCESSING,
						AutomationWorkflowRunStatus.WAITING_EVENT,
						AutomationWorkflowRunStatus.WAITING_APPROVAL
				),
				AutomationWorkflowRunStatus.CANCELLED,
				Instant.now(),
				"Сценарий удалён"
		);
		repository.deleteById(id);
	}


	@Transactional
	public List<AutomationTrigger> resort(List<AutomationTriggerDto> ordered) {
		for (int i = 0; i < ordered.size(); i++) {
			Long id = ordered.get(i).getId();
			if (id == null) continue;
			AutomationTrigger entity = repository.findById(id).orElseThrow();
			entity.setOrderNumber(i);
			repository.save(entity);
		}
		return repository.findAll().stream().sorted().toList();
	}


	private void validate(AutomationTriggerDto dto) {
		if (dto == null) {
			throw new IllegalArgumentException("trigger is required");
		}
		if (dto.getName() == null || dto.getName().isBlank()) {
			throw new IllegalArgumentException("name required");
		}
		if (dto.getTriggerType() == null || dto.getTriggerType().isBlank()) {
			throw new IllegalArgumentException("eventType required");
		}
		try {
			TriggerType.valueOf(dto.getTriggerType());
		} catch (Exception e) {
			throw new IllegalArgumentException("unknown eventType: " + dto.getTriggerType());
		}

		if (dto.getWorkflowDefinition() != null && !dto.getWorkflowDefinition().isBlank()) {
			workflowEngine.normalize(dto.getWorkflowDefinition());
			return;
		}
		if (dto.getExpression() == null || dto.getExpression().isBlank()) {
			throw new IllegalArgumentException("expression required");
		}
		if (dto.getAction() == null || dto.getAction().isBlank()) {
			throw new IllegalArgumentException("action required");
		}
	}


	private TriggerType resolveTriggerType(AutomationTriggerDto dto, String normalizedWorkflow) {
		if (normalizedWorkflow != null) {
			return workflowEngine.triggerType(normalizedWorkflow);
		}
		return TriggerType.valueOf(dto.getTriggerType());
	}


	private String normalizeWorkflow(String workflowDefinition) {
		if (workflowDefinition == null || workflowDefinition.isBlank()) {
			return null;
		}
		return workflowEngine.normalize(workflowDefinition);
	}


	private String normalizeLegacyExpression(AutomationTriggerDto dto, String workflowDefinition) {
		if (workflowDefinition != null) {
			return dto.getExpression() == null || dto.getExpression().isBlank()
					? "1 = 1"
					: dto.getExpression().trim();
		}
		return dto.getExpression().trim();
	}


	private String normalizeLegacyAction(AutomationTriggerDto dto, String workflowDefinition) {
		if (workflowDefinition != null) {
			return dto.getAction() == null || dto.getAction().isBlank()
					? "workflow.run()"
					: dto.getAction().trim();
		}
		return dto.getAction().trim();
	}


	private String normalizeOptionalAction(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}


	private AutomationRuleStatus resolveStatus(String value) {
		if (value == null || value.isBlank()) {
			return AutomationRuleStatus.ENABLED;
		}
		try {
			return AutomationRuleStatus.valueOf(value);
		} catch (Exception ignored) {
			return AutomationRuleStatus.ENABLED;
		}
	}


	private AutomationTriggerDto toDto(AutomationTrigger entity) {
		return AutomationTriggerDto.builder()
				.id(entity.getId())
				.name(entity.getName())
				.description(entity.getDescription())
				.triggerType(entity.getTriggerType().name())
				.expression(entity.getExpression())
				.action(entity.getAction())
				.elseAction(entity.getElseAction())
				.workflowDefinition(entity.getWorkflowDefinition())
				.workflowVersion(entity.getWorkflowVersion() == null ? 1 : entity.getWorkflowVersion())
				.orderNumber(entity.getOrderNumber())
				.automationRuleStatus(entity.getAutomationRuleStatus().name())
				.stopProcessing(Boolean.TRUE.equals(entity.getStopProcessing()))
				.matchCount(entity.getMatchCount() == null ? 0L : entity.getMatchCount())
				.lastMatchedAt(entity.getLastMatchedAt())
				.failureCount(entity.getFailureCount() == null ? 0L : entity.getFailureCount())
				.lastFailedAt(entity.getLastFailedAt())
				.lastError(entity.getLastError())
				.build();
	}
}

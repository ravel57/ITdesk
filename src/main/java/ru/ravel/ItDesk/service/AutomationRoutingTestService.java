package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationRoutingTestRequest;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.util.LinkedHashMap;
import java.util.Map;


@Service
public class AutomationRoutingTestService {

	private final TaskRepository taskRepository;
	private final ClientRepository clientRepository;
	private final AutomationScriptRuntime scriptRuntime;
	private final AutomationWorkflowEngine workflowEngine;
	private final AutomationPayloadService payloadService;
	private final ObjectMapper objectMapper;


	public AutomationRoutingTestService(
			TaskRepository taskRepository,
			ClientRepository clientRepository,
			AutomationScriptRuntime scriptRuntime,
			AutomationWorkflowEngine workflowEngine,
			AutomationPayloadService payloadService,
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper
	) {
		this.taskRepository = taskRepository;
		this.clientRepository = clientRepository;
		this.scriptRuntime = scriptRuntime;
		this.workflowEngine = workflowEngine;
		this.payloadService = payloadService;
		this.objectMapper = objectMapper;
	}


	@Transactional(readOnly = true)
	public Map<String, Object> test(AutomationRoutingTestRequest request) {
		if (request == null || request.getTaskId() == null) {
			throw new IllegalArgumentException("taskId is required");
		}
		Task task = taskRepository.findById(request.getTaskId()).orElseThrow();
		Client client = clientRepository.findByTaskId(task.getId()).orElseThrow();

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("task", Map.of("id", task.getId()));
		payload.put("client", Map.of("id", client.getId()));
		payload.put("changes", Map.of());
		Event testEvent = Event.builder().payload(objectMapper.valueToTree(payload)).build();
		payloadService.enrich(testEvent);
		JsonNode payloadNode = testEvent.getPayload();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("taskId", task.getId());
		result.put("taskName", task.getName());
		result.put("supportLine", task.getSupportLine() == null ? null : task.getSupportLine().getName());
		result.put("executor", task.getExecutor() == null ? null : task.getExecutor().getUsername());

		if (request.getWorkflowDefinition() != null && !request.getWorkflowDefinition().isBlank()) {
			result.putAll(workflowEngine.test(request.getWorkflowDefinition(), payloadNode));
			result.put("mode", "WORKFLOW");
			return result;
		}

		result.put("mode", "SCRIPT");
		result.put("expression", request.getExpression());
		result.put("action", request.getAction());
		result.put("elseAction", request.getElseAction());
		try {
			boolean matched = scriptRuntime.evaluateExpression(request.getExpression(), payloadNode);
			String selectedAction = matched ? request.getAction() : request.getElseAction();
			result.put("valid", true);
			result.put("matched", matched);
			result.put("branch", matched ? "YES" : selectedAction == null || selectedAction.isBlank() ? "NONE" : "NO");
			result.put("selectedAction", selectedAction);
		} catch (Exception e) {
			result.put("valid", false);
			result.put("matched", false);
			result.put("error", e.getMessage());
		}
		return result;
	}
}

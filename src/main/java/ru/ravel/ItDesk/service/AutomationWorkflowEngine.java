package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.dto.AutomationWorkflowDefinition;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.AutomationWorkflowRun;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.automatosation.AutomationWorkflowRunStatus;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationTriggerRepository;
import ru.ravel.ItDesk.repository.AutomationWorkflowRunRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;


@Slf4j
@Service
public class AutomationWorkflowEngine {

	private static final int MAX_NODES = 300;
	private static final int MAX_EDGES = 700;
	private static final int MAX_EXECUTION_STEPS = 500;
	private static final int MAX_SUBFLOW_DEPTH = 8;

	private static final Set<String> SUPPORTED_TYPES = Set.of(
			"EVENT", "CONDITION", "SWITCH", "ACTION", "DELAY", "WAIT_UNTIL", "WAIT_EVENT", "WAIT_TASK",
			"APPROVAL", "SUBFLOW", "FORK", "JOIN", "HTTP_REQUEST", "SET_VARIABLE", "EXPRESSION", "COUNTER",
			"ERROR_HANDLER", "RETRY", "ESCALATE", "CREATE_TASK", "NOTIFY", "BUSINESS_HOURS", "THROTTLE",
			"DEDUPLICATE", "NOTE", "END"
	);

	private static final Set<String> ASYNC_NODE_TYPES = Set.of(
			"DELAY", "WAIT_UNTIL", "WAIT_EVENT", "WAIT_TASK", "APPROVAL", "RETRY"
	);

	private final ObjectMapper objectMapper;
	private final AutomationScriptRuntime scriptRuntime;
	private final AutomationWorkflowRunRepository workflowRunRepository;
	private final AutomationTriggerRepository triggerRepository;
	private final AutomationPayloadService payloadService;
	private final AutomationWorkflowValueService valueService;
	private final AutomationWorkflowNodeService nodeService;


	public AutomationWorkflowEngine(
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper,
			AutomationScriptRuntime scriptRuntime,
			AutomationWorkflowRunRepository workflowRunRepository,
			AutomationTriggerRepository triggerRepository,
			AutomationPayloadService payloadService,
			AutomationWorkflowValueService valueService,
			AutomationWorkflowNodeService nodeService
	) {
		this.objectMapper = objectMapper;
		this.scriptRuntime = scriptRuntime;
		this.workflowRunRepository = workflowRunRepository;
		this.triggerRepository = triggerRepository;
		this.payloadService = payloadService;
		this.valueService = valueService;
		this.nodeService = nodeService;
	}


	public AutomationWorkflowDefinition parse(String workflowDefinition) {
		if (workflowDefinition == null || workflowDefinition.isBlank()) {
			throw new IllegalArgumentException("workflowDefinition is blank");
		}
		try {
			return objectMapper.readValue(workflowDefinition, AutomationWorkflowDefinition.class);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Некорректный JSON графа: " + e.getOriginalMessage(), e);
		}
	}


	public String normalize(String workflowDefinition) {
		AutomationWorkflowDefinition definition = parse(workflowDefinition);
		ValidationResult validation = validate(definition);
		if (!validation.errors().isEmpty()) {
			throw new IllegalArgumentException(String.join("; ", validation.errors()));
		}
		try {
			return objectMapper.writeValueAsString(definition);
		} catch (JsonProcessingException e) {
			throw new IllegalArgumentException("Не удалось сохранить граф", e);
		}
	}


	public TriggerType triggerType(String workflowDefinition) {
		AutomationWorkflowDefinition definition = parseAndValidate(workflowDefinition);
		AutomationWorkflowDefinition.Node entryNode = nodeMap(definition).get(definition.getEntryNodeId());
		if (entryNode == null) {
			throw new IllegalArgumentException("Стартовый узел не найден");
		}
		return TriggerType.valueOf(configString(entryNode, "triggerType"));
	}


	public Map<String, Object> validateAsMap(String workflowDefinition) {
		Map<String, Object> result = new LinkedHashMap<>();
		try {
			AutomationWorkflowDefinition definition = parse(workflowDefinition);
			ValidationResult validation = validate(definition);
			result.put("valid", validation.errors().isEmpty());
			result.put("errors", validation.errors());
			result.put("warnings", validation.warnings());
			result.put("nodeCount", safeNodes(definition).size());
			result.put("edgeCount", safeEdges(definition).size());
		} catch (Exception e) {
			result.put("valid", false);
			result.put("errors", List.of(e.getMessage()));
			result.put("warnings", List.of());
			result.put("nodeCount", 0);
			result.put("edgeCount", 0);
		}
		return result;
	}


	public ValidationResult validate(AutomationWorkflowDefinition definition) {
		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		if (definition == null) return new ValidationResult(List.of("Граф отсутствует"), List.of());

		List<AutomationWorkflowDefinition.Node> nodes = safeNodes(definition);
		List<AutomationWorkflowDefinition.Edge> edges = safeEdges(definition);
		if (nodes.isEmpty()) return new ValidationResult(List.of("Добавьте хотя бы один узел"), List.of());
		if (nodes.size() > MAX_NODES) errors.add("Слишком много узлов: максимум " + MAX_NODES);
		if (edges.size() > MAX_EDGES) errors.add("Слишком много связей: максимум " + MAX_EDGES);

		Map<String, AutomationWorkflowDefinition.Node> nodeById = new LinkedHashMap<>();
		for (AutomationWorkflowDefinition.Node node : nodes) {
			if (node == null || blank(node.getId())) {
				errors.add("У каждого узла должен быть id");
				continue;
			}
			if (nodeById.put(node.getId(), node) != null) errors.add("Повторяющийся id узла: " + node.getId());
			validateNode(node, nodeById, errors);
		}

		if (blank(definition.getEntryNodeId())) errors.add("Не указан стартовый узел");
		else if (!nodeById.containsKey(definition.getEntryNodeId())) errors.add("Стартовый узел не найден: " + definition.getEntryNodeId());
		else if (!"EVENT".equals(normalizeNodeType(nodeById.get(definition.getEntryNodeId()).getType()))) errors.add("Стартовым должен быть узел события");

		long eventCount = nodes.stream().filter(node -> node != null && "EVENT".equals(normalizeNodeType(node.getType()))).count();
		if (eventCount != 1) errors.add("В графе должен быть ровно один узел события");

		Set<String> edgeIds = new HashSet<>();
		Map<String, Integer> outgoingByHandle = new HashMap<>();
		Map<String, Integer> incomingCount = new HashMap<>();
		for (AutomationWorkflowDefinition.Edge edge : edges) {
			if (edge == null || blank(edge.getSource()) || blank(edge.getTarget())) {
				errors.add("Связь должна содержать source и target");
				continue;
			}
			if (!blank(edge.getId()) && !edgeIds.add(edge.getId())) errors.add("Повторяющийся id связи: " + edge.getId());
			if (!nodeById.containsKey(edge.getSource())) errors.add("Источник связи не найден: " + edge.getSource());
			if (!nodeById.containsKey(edge.getTarget())) errors.add("Цель связи не найдена: " + edge.getTarget());
			if (Objects.equals(edge.getSource(), edge.getTarget())) errors.add("Узел не может быть связан сам с собой: " + edge.getSource());
			String handle = normalizeHandle(edge.getSourceHandle());
			String key = edge.getSource() + "::" + handle;
			if (outgoingByHandle.merge(key, 1, Integer::sum) > 1) errors.add("От одного выхода может идти только одна связь: " + key);
			incomingCount.merge(edge.getTarget(), 1, Integer::sum);
		}

		for (AutomationWorkflowDefinition.Node node : nodes) {
			if (node == null || blank(node.getId()) || blank(node.getType())) continue;
			String type = normalizeNodeType(node.getType());
			if (!"EVENT".equals(type) && incomingCount.getOrDefault(node.getId(), 0) == 0) warnings.add("Узел недостижим: " + nodeLabel(node));
			if ("END".equals(type)) {
				if (hasOutgoing(edges, node.getId())) errors.add("У конечного узла не должно быть исходящих связей: " + nodeLabel(node));
				continue;
			}
			for (String handle : requiredHandles(node)) {
				if (!hasOutgoing(edges, node.getId(), handle)) {
					errors.add("У узла «" + nodeLabel(node) + "» отсутствует выход «" + handle + "»");
				}
			}
			if ("FORK".equals(type)) {
				String joinId = configString(node, "joinNodeId");
				AutomationWorkflowDefinition.Node join = nodeById.get(joinId);
				if (join == null || !"JOIN".equals(normalizeNodeType(join.getType()))) {
					errors.add("У FORK «" + nodeLabel(node) + "» не выбран корректный JOIN");
				} else {
					validateForkBranches(node, joinId, nodeById, edges, errors);
				}
			}
		}

		if (errors.isEmpty()) {
			Set<String> reachable = reachableNodes(definition.getEntryNodeId(), edges);
			for (AutomationWorkflowDefinition.Node node : nodes) {
				if (node != null && !blank(node.getId()) && !reachable.contains(node.getId())) warnings.add("Узел не входит в выполняемую цепочку: " + nodeLabel(node));
			}
			if (containsCycle(nodes, edges)) errors.add("Произвольные циклические связи запрещены. Используйте узел RETRY");
		}

		return new ValidationResult(List.copyOf(errors), List.copyOf(warnings));
	}


	@Transactional
	public ExecutionResult execute(AutomationTrigger trigger, Event event) {
		AutomationWorkflowDefinition definition = parseAndValidate(trigger.getWorkflowDefinition());
		return executeFrom(definition, definition.getEntryNodeId(), trigger, event, 0, false, null, 0, true);
	}


	@Transactional
	public ExecutionResult resume(AutomationTrigger trigger, AutomationWorkflowRun run) {
		String snapshot = run.getWorkflowDefinition();
		AutomationWorkflowDefinition definition = parseAndValidate(snapshot == null || snapshot.isBlank() ? trigger.getWorkflowDefinition() : snapshot);
		Event event = Event.builder()
				.id(run.getSourceEventId())
				.triggerType(run.getTriggerType())
				.payload(run.getEventPayload().deepCopy())
				.actorUserId(run.getActorUserId())
				.actorUsername(run.getActorUsername())
				.actorDisplayName(run.getActorDisplayName())
				.actorType(run.getActorType())
				.build();
		payloadService.enrich(event);
		return executeFrom(definition, run.getCurrentNodeId(), trigger, event, run.getStepCount() == null ? 0 : run.getStepCount(), false, null, 0, true);
	}


	@Transactional(readOnly = true)
	public Map<String, Object> test(String workflowDefinition, JsonNode payload) {
		Map<String, Object> result = new LinkedHashMap<>();
		try {
			AutomationWorkflowDefinition definition = parseAndValidate(workflowDefinition);
			Event event = Event.builder().payload(payload.deepCopy()).build();
			ExecutionResult execution = executeFrom(definition, definition.getEntryNodeId(), null, event, 0, true, null, 0, true);
			result.put("valid", true);
			result.put("completed", execution.completed());
			result.put("scheduled", execution.scheduled());
			result.put("executed", execution.executed());
			result.put("trace", execution.trace());
			result.put("stoppedNodeId", execution.stoppedNodeId());
			result.put("payload", event.getPayload());
		} catch (Exception e) {
			result.put("valid", false);
			result.put("completed", false);
			result.put("scheduled", false);
			result.put("executed", false);
			result.put("trace", List.of());
			result.put("error", e.getMessage());
		}
		return result;
	}


	private ExecutionResult executeFrom(
			AutomationWorkflowDefinition definition,
			String startNodeId,
			AutomationTrigger trigger,
			Event event,
			int initialStepCount,
			boolean dryRun,
			String stopAtNodeId,
			int subflowDepth,
			boolean allowAsync
	) {
		Map<String, AutomationWorkflowDefinition.Node> nodes = nodeMap(definition);
		List<AutomationWorkflowDefinition.Edge> edges = safeEdges(definition);
		List<TraceStep> trace = new ArrayList<>();
		String currentNodeId = startNodeId;
		int steps = initialStepCount;
		boolean executed = false;

		while (!blank(currentNodeId)) {
			if (Objects.equals(currentNodeId, stopAtNodeId)) {
				return new ExecutionResult(executed, false, true, currentNodeId, List.copyOf(trace), steps);
			}
			if (++steps > MAX_EXECUTION_STEPS) throw new IllegalStateException("Превышен лимит шагов автоматизации: " + MAX_EXECUTION_STEPS);
			AutomationWorkflowDefinition.Node node = nodes.get(currentNodeId);
			if (node == null) throw new IllegalStateException("Узел не найден: " + currentNodeId);
			String type = normalizeNodeType(node.getType());

			switch (type) {
				case "EVENT", "NOTE", "JOIN" -> {
					trace.add(trace(node, "PASSED", type.equals("EVENT") ? "Событие принято" : type.equals("JOIN") ? "Ветки объединены" : "Комментарий"));
					currentNodeId = target(edges, node.getId(), "next");
				}
				case "CONDITION" -> {
					String expression = configString(node, "expression");
					boolean matched = scriptRuntime.evaluateExpression(expression, event.getPayload());
					trace.add(trace(node, matched ? "TRUE" : "FALSE", expression));
					currentNodeId = target(edges, node.getId(), matched ? "true" : "false");
				}
				case "SWITCH" -> {
					Object value = valueService.resolve(event.getPayload(), configString(node, "valueExpression"));
					String handle = "default";
					for (Map<String, Object> item : configList(node, "cases")) {
						if (valueService.valuesEqual(value, item.get("value"))) {
							handle = "case:" + item.get("id");
							break;
						}
					}
					trace.add(trace(node, "ROUTED", String.valueOf(value)));
					currentNodeId = target(edges, node.getId(), handle);
				}
				case "ACTION" -> {
					String actionScript = configString(node, "actionScript");
					boolean continueOnError = configBoolean(node, "continueOnError", false);
					try {
						if (!dryRun) {
							scriptRuntime.executeActions(actionScript, new AutomationExecutionContext(trigger, event));
							payloadService.enrich(event);
						}
						executed = true;
						trace.add(trace(node, dryRun ? "WOULD_EXECUTE" : "EXECUTED", actionScript));
					} catch (Exception e) {
						trace.add(trace(node, "FAILED", e.getMessage()));
						if (!continueOnError) throw runtime(e);
					}
					currentNodeId = target(edges, node.getId(), "next");
				}
				case "DELAY", "WAIT_UNTIL" -> {
					if (!allowAsync) throw new IllegalStateException("Асинхронный узел нельзя использовать внутри FORK или встроенного подпроцесса: " + nodeLabel(node));
					Instant availableAt = type.equals("DELAY")
							? Instant.now().plusSeconds(delaySeconds(node))
							: nodeService.waitUntil(node, event);
					String next = target(edges, node.getId(), "next");
					trace.add(trace(node, dryRun ? "WOULD_WAIT" : "SCHEDULED", availableAt.toString()));
					if (!dryRun) scheduleContinuation(trigger, event, next, steps, availableAt);
					return new ExecutionResult(executed, true, false, node.getId(), List.copyOf(trace), steps);
				}
				case "WAIT_EVENT", "WAIT_TASK" -> {
					if (!allowAsync) throw new IllegalStateException("Ожидание события нельзя использовать внутри FORK или встроенного подпроцесса");
					TriggerType waitType = TriggerType.valueOf(configString(node, "eventType"));
					long timeout = nodeService.durationSeconds(configLong(node, "timeoutAmount", 24), configString(node, "timeoutUnit", "HOURS"));
					String correlation = type.equals("WAIT_TASK")
							? "task:" + Objects.toString(valueService.resolveLong(event.getPayload(), configString(node, "taskIdExpression")), "none")
							: valueService.correlationKey(event.getPayload(), configString(node, "scope", "TASK"), null);
					trace.add(trace(node, dryRun ? "WOULD_WAIT_EVENT" : "WAITING_EVENT", waitType + " / " + correlation));
					if (!dryRun) scheduleWaitEvent(trigger, event, node, steps, waitType, correlation, timeout,
							target(edges, node.getId(), "event"), target(edges, node.getId(), "timeout"));
					return new ExecutionResult(executed, true, false, node.getId(), List.copyOf(trace), steps);
				}
				case "APPROVAL" -> {
					if (!allowAsync) throw new IllegalStateException("Ручное решение нельзя использовать внутри FORK или встроенного подпроцесса");
					long timeout = nodeService.durationSeconds(configLong(node, "timeoutAmount", 24), configString(node, "timeoutUnit", "HOURS"));
					trace.add(trace(node, dryRun ? "WOULD_REQUEST_APPROVAL" : "WAITING_APPROVAL", configString(node, "title")));
					if (!dryRun) scheduleApproval(trigger, event, node, steps, timeout,
							target(edges, node.getId(), "approved"), target(edges, node.getId(), "rejected"), target(edges, node.getId(), "timeout"));
					return new ExecutionResult(executed, true, false, node.getId(), List.copyOf(trace), steps);
				}
				case "SUBFLOW" -> {
					if (subflowDepth >= MAX_SUBFLOW_DEPTH) throw new IllegalStateException("Превышена глубина подпроцессов");
					Long childId = configNullableLong(node, "triggerId");
					AutomationTrigger child = childId == null ? null : triggerRepository.findById(childId).orElse(null);
					if (child == null || blank(child.getWorkflowDefinition())) {
						trace.add(trace(node, "FAILED", "Подпроцесс не найден"));
						currentNodeId = target(edges, node.getId(), "error");
						break;
					}
					try {
						String mode = configString(node, "mode", "INLINE").toUpperCase(Locale.ROOT);
						if ("ASYNC".equals(mode)) {
							if (!allowAsync) throw new IllegalStateException("Асинхронный подпроцесс нельзя использовать внутри FORK или встроенного подпроцесса");
							if (!dryRun) scheduleSubflow(child, event);
							trace.add(trace(node, dryRun ? "WOULD_START" : "STARTED", child.getName()));
						} else {
							AutomationWorkflowDefinition childDefinition = parseAndValidate(child.getWorkflowDefinition());
							ExecutionResult childResult = executeFrom(childDefinition, childDefinition.getEntryNodeId(), child, event, steps, dryRun, null, subflowDepth + 1, false);
							trace.addAll(childResult.trace());
							steps = childResult.stepCount();
							executed = executed || childResult.executed();
						}
						currentNodeId = target(edges, node.getId(), "success");
					} catch (Exception e) {
						valueService.setVariable(event.getPayload(), "lastError", e.getMessage());
						trace.add(trace(node, "FAILED", e.getMessage()));
						currentNodeId = target(edges, node.getId(), "error");
					}
				}
				case "FORK" -> {
					String joinId = configString(node, "joinNodeId");
					trace.add(trace(node, "FORKED", configList(node, "branches").size() + " веток"));
					for (Map<String, Object> branch : configList(node, "branches")) {
						String branchTarget = target(edges, node.getId(), "branch:" + branch.get("id"));
						ExecutionResult branchResult = executeFrom(definition, branchTarget, trigger, event, steps, dryRun, joinId, subflowDepth, false);
						trace.addAll(branchResult.trace());
						steps = branchResult.stepCount();
						executed = executed || branchResult.executed();
					}
					AutomationWorkflowDefinition.Node join = nodes.get(joinId);
					if (join != null) trace.add(trace(join, "JOINED", "Все ветки завершены"));
					currentNodeId = target(edges, joinId, "next");
				}
				case "HTTP_REQUEST" -> {
					AutomationWorkflowNodeService.HttpResult result = nodeService.http(node, event, dryRun);
					trace.add(trace(node, result.success() ? (dryRun ? "WOULD_REQUEST" : "SUCCESS") : "FAILED", result.status() + " " + Objects.toString(result.body(), "")));
					executed = true;
					currentNodeId = target(edges, node.getId(), result.success() ? "success" : "error");
				}
				case "SET_VARIABLE", "EXPRESSION" -> {
					Object value = valueService.resolve(event.getPayload(), configString(node, "expression"));
					valueService.setVariable(event.getPayload(), configString(node, "name"), value);
					trace.add(trace(node, "SET", String.valueOf(value)));
					currentNodeId = target(edges, node.getId(), "next");
				}
				case "COUNTER" -> {
					String name = configString(node, "name");
					long current = valueService.getLongVariable(event.getPayload(), name, 0L);
					Long amountValue = valueService.resolveLong(event.getPayload(), configString(node, "amountExpression", "1"));
					long amount = amountValue == null ? 1L : amountValue;
					long updated = switch (configString(node, "operation", "ADD").toUpperCase(Locale.ROOT)) {
						case "SET" -> amount;
						case "SUBTRACT" -> current - amount;
						default -> current + amount;
					};
					valueService.setVariable(event.getPayload(), name, updated);
					trace.add(trace(node, "SET", name + "=" + updated));
					currentNodeId = target(edges, node.getId(), "next");
				}
				case "ERROR_HANDLER" -> {
					try {
						if (!dryRun) scriptRuntime.executeActions(configString(node, "actionScript"), new AutomationExecutionContext(trigger, event));
						executed = true;
						trace.add(trace(node, dryRun ? "WOULD_EXECUTE" : "SUCCESS", configString(node, "actionScript")));
						currentNodeId = target(edges, node.getId(), "success");
					} catch (Exception e) {
						valueService.setVariable(event.getPayload(), "lastError", e.getMessage());
						trace.add(trace(node, "ERROR", e.getMessage()));
						currentNodeId = target(edges, node.getId(), "error");
					}
				}
				case "RETRY" -> {
					String key = "retry_" + node.getId().replaceAll("[^a-zA-Z0-9_]", "_");
					long attempt = valueService.getLongVariable(event.getPayload(), key, 0L) + 1L;
					try {
						if (!dryRun) scriptRuntime.executeActions(configString(node, "actionScript"), new AutomationExecutionContext(trigger, event));
						valueService.setVariable(event.getPayload(), key, 0L);
						executed = true;
						trace.add(trace(node, dryRun ? "WOULD_EXECUTE" : "SUCCESS", "Попытка " + attempt));
						currentNodeId = target(edges, node.getId(), "success");
					} catch (Exception e) {
						long max = Math.max(1L, configLong(node, "maxAttempts", 3L));
						if (attempt < max) {
							if (!allowAsync) throw new IllegalStateException("RETRY с задержкой нельзя использовать внутри FORK или встроенного подпроцесса", e);
							valueService.setVariable(event.getPayload(), key, attempt);
							double multiplier = Math.max(1D, configDouble(node, "multiplier", 2D));
							long base = nodeService.durationSeconds(configLong(node, "delayAmount", 1L), configString(node, "delayUnit", "MINUTES"));
							long delay = Math.max(1L, Math.round(base * Math.pow(multiplier, Math.max(0L, attempt - 1L))));
							trace.add(trace(node, dryRun ? "WOULD_RETRY" : "RETRY_SCHEDULED", "Попытка " + attempt + "/" + max + ", через " + delay + " сек."));
							if (!dryRun) scheduleContinuation(trigger, event, node.getId(), steps, Instant.now().plusSeconds(delay));
							return new ExecutionResult(executed, true, false, node.getId(), List.copyOf(trace), steps);
						}
						valueService.setVariable(event.getPayload(), "lastError", e.getMessage());
						trace.add(trace(node, "FAILED", "Попытки исчерпаны: " + e.getMessage()));
						currentNodeId = target(edges, node.getId(), "failed");
					}
				}
				case "ESCALATE", "CREATE_TASK", "NOTIFY" -> {
					try {
						if ("ESCALATE".equals(type)) nodeService.escalate(trigger, node, event, dryRun);
						else if ("CREATE_TASK".equals(type)) nodeService.createTask(node, event, dryRun);
						else nodeService.notify(trigger, node, event, dryRun);
						executed = true;
						trace.add(trace(node, dryRun ? "WOULD_EXECUTE" : "SUCCESS", type));
						currentNodeId = target(edges, node.getId(), "success");
					} catch (Exception e) {
						valueService.setVariable(event.getPayload(), "lastError", e.getMessage());
						trace.add(trace(node, "FAILED", e.getMessage()));
						currentNodeId = target(edges, node.getId(), "error");
					}
				}
				case "BUSINESS_HOURS" -> {
					AutomationWorkflowNodeService.BusinessPeriod period = nodeService.businessPeriod();
					String handle = switch (period) {
						case WORKING -> "working";
						case WEEKEND -> "weekend";
						case AFTER_HOURS -> "after_hours";
					};
					trace.add(trace(node, "ROUTED", period.name()));
					currentNodeId = target(edges, node.getId(), handle);
				}
				case "THROTTLE" -> {
					boolean allowed = nodeService.throttle(trigger, node, event, dryRun);
					trace.add(trace(node, allowed ? "ALLOWED" : "BLOCKED", configString(node, "scope", "TASK")));
					currentNodeId = target(edges, node.getId(), allowed ? "allowed" : "blocked");
				}
				case "DEDUPLICATE" -> {
					Task duplicate = nodeService.findDuplicate(node, event);
					trace.add(trace(node, duplicate == null ? "UNIQUE" : "DUPLICATE", duplicate == null ? "Не найден" : "taskId=" + duplicate.getId()));
					currentNodeId = target(edges, node.getId(), duplicate == null ? "unique" : "duplicate");
				}
				case "END" -> {
					trace.add(trace(node, "COMPLETED", configString(node, "result")));
					return new ExecutionResult(executed, false, true, node.getId(), List.copyOf(trace), steps);
				}
				default -> throw new IllegalStateException("Неподдерживаемый тип узла: " + type);
			}
		}
		return new ExecutionResult(executed, false, true, null, List.copyOf(trace), steps);
	}


	private void scheduleContinuation(AutomationTrigger trigger, Event event, String nextNodeId, int stepCount, Instant availableAt) {
		ensurePersistedTrigger(trigger);
		if (blank(nextNodeId)) throw new IllegalStateException("После ожидания отсутствует следующий узел");
		workflowRunRepository.save(baseRun(trigger, event, nextNodeId, stepCount)
				.status(AutomationWorkflowRunStatus.NEW)
				.availableAt(availableAt)
				.build());
	}


	private void scheduleWaitEvent(
			AutomationTrigger trigger,
			Event event,
			AutomationWorkflowDefinition.Node node,
			int stepCount,
			TriggerType waitType,
			String correlation,
			long timeoutSeconds,
			String resumeNodeId,
			String timeoutNodeId
	) {
		ensurePersistedTrigger(trigger);
		workflowRunRepository.save(baseRun(trigger, event, node.getId(), stepCount)
				.status(AutomationWorkflowRunStatus.WAITING_EVENT)
				.availableAt(Instant.now().plusSeconds(timeoutSeconds))
				.waitKind(normalizeNodeType(node.getType()))
				.waitEventType(waitType)
				.correlationKey(correlation)
				.resumeNodeId(resumeNodeId)
				.timeoutNodeId(timeoutNodeId)
				.waitExpression(configString(node, "filterExpression"))
				.build());
	}


	private void scheduleApproval(
			AutomationTrigger trigger,
			Event event,
			AutomationWorkflowDefinition.Node node,
			int stepCount,
			long timeoutSeconds,
			String approvedNodeId,
			String rejectedNodeId,
			String timeoutNodeId
	) {
		ensurePersistedTrigger(trigger);
		workflowRunRepository.save(baseRun(trigger, event, node.getId(), stepCount)
				.status(AutomationWorkflowRunStatus.WAITING_APPROVAL)
				.availableAt(Instant.now().plusSeconds(timeoutSeconds))
				.waitKind("APPROVAL")
				.resumeNodeId(approvedNodeId)
				.rejectedNodeId(rejectedNodeId)
				.timeoutNodeId(timeoutNodeId)
				.approverUserId(configNullableLong(node, "approverUserId"))
				.approvalTitle(valueService.renderTemplate(event.getPayload(), configString(node, "title")))
				.approvalMessage(valueService.renderTemplate(event.getPayload(), configString(node, "message")))
				.build());
	}


	private void scheduleSubflow(AutomationTrigger child, Event event) {
		AutomationWorkflowDefinition definition = parseAndValidate(child.getWorkflowDefinition());
		workflowRunRepository.save(baseRun(child, event, definition.getEntryNodeId(), 0)
				.status(AutomationWorkflowRunStatus.NEW)
				.availableAt(Instant.now())
				.build());
	}


	private AutomationWorkflowRun.AutomationWorkflowRunBuilder baseRun(AutomationTrigger trigger, Event event, String nodeId, int stepCount) {
		return AutomationWorkflowRun.builder()
				.triggerId(trigger.getId())
				.sourceEventId(event.getId())
				.triggerType(event.getTriggerType() == null ? trigger.getTriggerType() : event.getTriggerType())
				.eventPayload(event.getPayload().deepCopy())
				.workflowDefinition(trigger.getWorkflowDefinition())
				.currentNodeId(nodeId)
				.stepCount(stepCount)
				.retries(0)
				.actorUserId(event.getActorUserId())
				.actorUsername(event.getActorUsername())
				.actorDisplayName(event.getActorDisplayName())
				.actorType(event.getActorType());
	}


	private void ensurePersistedTrigger(AutomationTrigger trigger) {
		if (trigger == null || trigger.getId() == null) throw new IllegalStateException("Нельзя запланировать продолжение несохранённого сценария");
	}


	private void validateNode(
			AutomationWorkflowDefinition.Node node,
			Map<String, AutomationWorkflowDefinition.Node> knownNodes,
			List<String> errors
	) {
		if (blank(node.getType())) {
			errors.add("У узла не указан тип: " + node.getId());
			return;
		}
		String type = normalizeNodeType(node.getType());
		if (!SUPPORTED_TYPES.contains(type)) {
			errors.add("Неизвестный тип узла: " + node.getType());
			return;
		}
		switch (type) {
			case "EVENT" -> validateTriggerType(node, "triggerType", errors);
			case "CONDITION" -> require(node, "expression", "У условия не заполнено выражение", errors);
			case "SWITCH" -> {
				require(node, "valueExpression", "У SWITCH не указано значение", errors);
				if (configList(node, "cases").isEmpty()) errors.add("У SWITCH нет вариантов: " + nodeLabel(node));
			}
			case "ACTION", "ERROR_HANDLER", "RETRY" -> require(node, "actionScript", "В узле нет сценария действий", errors);
			case "DELAY" -> validateDuration(node, "amount", "unit", errors);
			case "WAIT_UNTIL" -> require(node, "mode", "Не выбран режим ожидания", errors);
			case "WAIT_EVENT", "WAIT_TASK" -> {
				validateTriggerType(node, "eventType", errors);
				validateDuration(node, "timeoutAmount", "timeoutUnit", errors);
				if ("WAIT_TASK".equals(type)) require(node, "taskIdExpression", "Не указан ID ожидаемой заявки", errors);
			}
			case "APPROVAL" -> {
				require(node, "title", "Не заполнен заголовок ручного решения", errors);
				validateDuration(node, "timeoutAmount", "timeoutUnit", errors);
			}
			case "SUBFLOW" -> {
				if (configNullableLong(node, "triggerId") == null) errors.add("Не выбран подпроцесс: " + nodeLabel(node));
			}
			case "FORK" -> {
				if (configList(node, "branches").size() < 2) errors.add("У FORK должно быть минимум две ветки: " + nodeLabel(node));
				require(node, "joinNodeId", "У FORK не выбран JOIN", errors);
			}
			case "HTTP_REQUEST" -> {
				require(node, "method", "Не выбран HTTP-метод", errors);
				require(node, "url", "Не заполнен URL", errors);
			}
			case "SET_VARIABLE", "EXPRESSION", "COUNTER" -> require(node, "name", "Не указано имя переменной", errors);
			case "ESCALATE" -> {
				if (configNullableLong(node, "supportLineId") == null) errors.add("Не выбрана линия эскалации: " + nodeLabel(node));
			}
			case "CREATE_TASK" -> require(node, "title", "Не заполнено название создаваемой заявки", errors);
			case "NOTIFY" -> require(node, "text", "Не заполнен текст уведомления", errors);
			case "THROTTLE" -> validateDuration(node, "amount", "unit", errors);
			case "DEDUPLICATE" -> {
				if (configLong(node, "windowMinutes", 0L) < 1) errors.add("Период поиска дубля должен быть больше нуля: " + nodeLabel(node));
			}
			case "END", "NOTE", "JOIN", "BUSINESS_HOURS" -> {
			}
		}
	}


	private List<String> requiredHandles(AutomationWorkflowDefinition.Node node) {
		String type = normalizeNodeType(node.getType());
		return switch (type) {
			case "END" -> List.of();
			case "CONDITION" -> List.of("true", "false");
			case "SWITCH" -> {
				List<String> handles = configList(node, "cases").stream().map(item -> "case:" + item.get("id")).collect(Collectors.toCollection(ArrayList::new));
				handles.add("default");
				yield handles;
			}
			case "WAIT_EVENT", "WAIT_TASK" -> List.of("event", "timeout");
			case "APPROVAL" -> List.of("approved", "rejected", "timeout");
			case "SUBFLOW", "HTTP_REQUEST", "ERROR_HANDLER", "ESCALATE", "CREATE_TASK", "NOTIFY" -> List.of("success", "error");
			case "RETRY" -> List.of("success", "failed");
			case "BUSINESS_HOURS" -> List.of("working", "after_hours", "weekend");
			case "THROTTLE" -> List.of("allowed", "blocked");
			case "DEDUPLICATE" -> List.of("duplicate", "unique");
			case "FORK" -> configList(node, "branches").stream().map(item -> "branch:" + item.get("id")).toList();
			default -> List.of("next");
		};
	}


	private void validateTriggerType(AutomationWorkflowDefinition.Node node, String key, List<String> errors) {
		String value = configString(node, key);
		if (blank(value)) {
			errors.add("Не выбран тип события: " + nodeLabel(node));
			return;
		}
		try {
			TriggerType.valueOf(value);
		} catch (Exception ignored) {
			errors.add("Неизвестный тип события: " + value);
		}
	}


	private void validateDuration(AutomationWorkflowDefinition.Node node, String amountKey, String unitKey, List<String> errors) {
		try {
			long seconds = nodeService.durationSeconds(configLong(node, amountKey, 0L), configString(node, unitKey, "MINUTES"));
			if (seconds < 1 || seconds > Duration.ofDays(365).toSeconds()) errors.add("Интервал должен быть от 1 секунды до 365 дней: " + nodeLabel(node));
		} catch (Exception e) {
			errors.add("Некорректный интервал: " + nodeLabel(node));
		}
	}


	private void require(AutomationWorkflowDefinition.Node node, String key, String message, List<String> errors) {
		if (blank(configString(node, key))) errors.add(message + ": " + nodeLabel(node));
	}


	private long delaySeconds(AutomationWorkflowDefinition.Node node) {
		return nodeService.durationSeconds(configLong(node, "amount", 0L), configString(node, "unit", "MINUTES"));
	}


	private TraceStep trace(AutomationWorkflowDefinition.Node node, String status, String detail) {
		return new TraceStep(node.getId(), normalizeNodeType(node.getType()), nodeLabel(node), status, detail);
	}


	private RuntimeException runtime(Exception e) {
		return e instanceof RuntimeException runtime ? runtime : new RuntimeException(e);
	}


	private String target(List<AutomationWorkflowDefinition.Edge> edges, String source, String handle) {
		return edges.stream()
				.filter(edge -> edge != null && Objects.equals(source, edge.getSource()))
				.filter(edge -> Objects.equals(normalizeHandle(edge.getSourceHandle()), normalizeHandle(handle)))
				.map(AutomationWorkflowDefinition.Edge::getTarget)
				.findFirst()
				.orElse(null);
	}


	private boolean hasOutgoing(List<AutomationWorkflowDefinition.Edge> edges, String source) {
		return edges.stream().anyMatch(edge -> edge != null && Objects.equals(source, edge.getSource()));
	}


	private boolean hasOutgoing(List<AutomationWorkflowDefinition.Edge> edges, String source, String handle) {
		return edges.stream().anyMatch(edge -> edge != null
				&& Objects.equals(source, edge.getSource())
				&& Objects.equals(normalizeHandle(edge.getSourceHandle()), normalizeHandle(handle)));
	}


	private Set<String> reachableNodes(String entryNodeId, List<AutomationWorkflowDefinition.Edge> edges) {
		Set<String> reached = new LinkedHashSet<>();
		Deque<String> queue = new ArrayDeque<>();
		if (!blank(entryNodeId)) queue.add(entryNodeId);
		while (!queue.isEmpty()) {
			String current = queue.removeFirst();
			if (!reached.add(current)) continue;
			for (AutomationWorkflowDefinition.Edge edge : edges) {
				if (edge != null && Objects.equals(current, edge.getSource()) && !blank(edge.getTarget())) queue.addLast(edge.getTarget());
			}
		}
		return reached;
	}


	private void validateForkBranches(
			AutomationWorkflowDefinition.Node fork,
			String joinId,
			Map<String, AutomationWorkflowDefinition.Node> nodeById,
			List<AutomationWorkflowDefinition.Edge> edges,
			List<String> errors
	) {
		for (Map<String, Object> branch : configList(fork, "branches")) {
			String branchId = Objects.toString(branch.get("id"), "").trim();
			String branchLabel = Objects.toString(branch.get("label"), branchId).trim();
			String startNodeId = target(edges, fork.getId(), "branch:" + branchId);
			if (blank(startNodeId)) continue;

			ForkPathValidation validation = validateForkPath(
					startNodeId,
					joinId,
					nodeById,
					edges,
					new LinkedHashSet<>()
			);
			if (!validation.reachesJoin()) {
				errors.add("Ветка FORK «" + branchLabel + "» должна завершаться выбранным JOIN: " + nodeLabel(fork));
			}
			if (validation.asyncNodeLabel() != null) {
				errors.add("Ветка FORK «" + branchLabel + "» содержит асинхронный узел «" + validation.asyncNodeLabel() + "». Вынесите ожидание после JOIN");
			}
		}
	}


	private ForkPathValidation validateForkPath(
			String nodeId,
			String joinId,
			Map<String, AutomationWorkflowDefinition.Node> nodeById,
			List<AutomationWorkflowDefinition.Edge> edges,
			Set<String> path
	) {
		if (Objects.equals(nodeId, joinId)) return new ForkPathValidation(true, null);
		if (blank(nodeId) || !path.add(nodeId)) return new ForkPathValidation(false, null);

		AutomationWorkflowDefinition.Node node = nodeById.get(nodeId);
		if (node == null) return new ForkPathValidation(false, null);
		String type = normalizeNodeType(node.getType());
		if (ASYNC_NODE_TYPES.contains(type) || ("SUBFLOW".equals(type) && "ASYNC".equalsIgnoreCase(configString(node, "mode", "INLINE")))) {
			return new ForkPathValidation(false, nodeLabel(node));
		}
		if ("END".equals(type)) return new ForkPathValidation(false, null);

		List<AutomationWorkflowDefinition.Edge> outgoing = edges.stream()
				.filter(Objects::nonNull)
				.filter(edge -> Objects.equals(nodeId, edge.getSource()))
				.toList();
		if (outgoing.isEmpty()) return new ForkPathValidation(false, null);

		boolean allReachJoin = true;
		String asyncNodeLabel = null;
		for (AutomationWorkflowDefinition.Edge edge : outgoing) {
			ForkPathValidation child = validateForkPath(
					edge.getTarget(),
					joinId,
					nodeById,
					edges,
					new LinkedHashSet<>(path)
			);
			allReachJoin = allReachJoin && child.reachesJoin();
			if (asyncNodeLabel == null) asyncNodeLabel = child.asyncNodeLabel();
		}
		return new ForkPathValidation(allReachJoin, asyncNodeLabel);
	}


	private boolean containsCycle(List<AutomationWorkflowDefinition.Node> nodes, List<AutomationWorkflowDefinition.Edge> edges) {
		Map<String, List<String>> adjacency = new HashMap<>();
		for (AutomationWorkflowDefinition.Edge edge : edges) {
			if (edge != null && !blank(edge.getSource()) && !blank(edge.getTarget())) adjacency.computeIfAbsent(edge.getSource(), ignored -> new ArrayList<>()).add(edge.getTarget());
		}
		Set<String> visiting = new HashSet<>();
		Set<String> visited = new HashSet<>();
		for (AutomationWorkflowDefinition.Node node : nodes) {
			if (node != null && !blank(node.getId()) && dfsCycle(node.getId(), adjacency, visiting, visited)) return true;
		}
		return false;
	}


	private boolean dfsCycle(String node, Map<String, List<String>> adjacency, Set<String> visiting, Set<String> visited) {
		if (blank(node) || visited.contains(node)) return false;
		if (!visiting.add(node)) return true;
		for (String next : adjacency.getOrDefault(node, List.of())) if (dfsCycle(next, adjacency, visiting, visited)) return true;
		visiting.remove(node);
		visited.add(node);
		return false;
	}


	private AutomationWorkflowDefinition parseAndValidate(String workflowDefinition) {
		AutomationWorkflowDefinition definition = parse(workflowDefinition);
		ValidationResult validation = validate(definition);
		if (!validation.errors().isEmpty()) throw new IllegalArgumentException(String.join("; ", validation.errors()));
		return definition;
	}


	private Map<String, AutomationWorkflowDefinition.Node> nodeMap(AutomationWorkflowDefinition definition) {
		return safeNodes(definition).stream()
				.filter(Objects::nonNull)
				.filter(node -> !blank(node.getId()))
				.collect(Collectors.toMap(AutomationWorkflowDefinition.Node::getId, node -> node, (left, right) -> left, LinkedHashMap::new));
	}


	private List<AutomationWorkflowDefinition.Node> safeNodes(AutomationWorkflowDefinition definition) {
		return definition == null || definition.getNodes() == null ? List.of() : definition.getNodes();
	}


	private List<AutomationWorkflowDefinition.Edge> safeEdges(AutomationWorkflowDefinition definition) {
		return definition == null || definition.getEdges() == null ? List.of() : definition.getEdges();
	}


	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> configList(AutomationWorkflowDefinition.Node node, String key) {
		Object value = node == null || node.getConfig() == null ? null : node.getConfig().get(key);
		if (!(value instanceof List<?> list)) return List.of();
		List<Map<String, Object>> result = new ArrayList<>();
		for (Object item : list) if (item instanceof Map<?, ?> map) result.add((Map<String, Object>) map);
		return result;
	}


	private String nodeLabel(AutomationWorkflowDefinition.Node node) {
		if (node == null) return "узел";
		return blank(node.getLabel()) ? node.getId() : node.getLabel();
	}


	private String normalizeNodeType(String type) {
		return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
	}


	private String normalizeHandle(String handle) {
		return blank(handle) ? "next" : handle.trim().toLowerCase(Locale.ROOT);
	}


	private String configString(AutomationWorkflowDefinition.Node node, String key) {
		return configString(node, key, null);
	}


	private String configString(AutomationWorkflowDefinition.Node node, String key, String fallback) {
		if (node == null || node.getConfig() == null) return fallback;
		Object value = node.getConfig().get(key);
		return value == null ? fallback : String.valueOf(value).trim();
	}


	private long configLong(AutomationWorkflowDefinition.Node node, String key, long fallback) {
		if (node == null || node.getConfig() == null) return fallback;
		Object value = node.getConfig().get(key);
		if (value instanceof Number number) return number.longValue();
		try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
	}


	private double configDouble(AutomationWorkflowDefinition.Node node, String key, double fallback) {
		if (node == null || node.getConfig() == null) return fallback;
		Object value = node.getConfig().get(key);
		if (value instanceof Number number) return number.doubleValue();
		try { return value == null ? fallback : Double.parseDouble(String.valueOf(value)); } catch (Exception ignored) { return fallback; }
	}


	private Long configNullableLong(AutomationWorkflowDefinition.Node node, String key) {
		if (node == null || node.getConfig() == null) return null;
		Object value = node.getConfig().get(key);
		if (value instanceof Number number) return number.longValue();
		try { return value == null || String.valueOf(value).isBlank() ? null : Long.parseLong(String.valueOf(value)); } catch (Exception ignored) { return null; }
	}


	private boolean configBoolean(AutomationWorkflowDefinition.Node node, String key, boolean fallback) {
		if (node == null || node.getConfig() == null) return fallback;
		Object value = node.getConfig().get(key);
		if (value instanceof Boolean bool) return bool;
		return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
	}


	private boolean blank(String value) {
		return value == null || value.isBlank();
	}


	private record ForkPathValidation(boolean reachesJoin, String asyncNodeLabel) {}
	public record ValidationResult(List<String> errors, List<String> warnings) {}
	public record TraceStep(String nodeId, String nodeType, String label, String status, String detail) {}
	public record ExecutionResult(boolean executed, boolean scheduled, boolean completed, String stoppedNodeId, List<TraceStep> trace, int stepCount) {}
}

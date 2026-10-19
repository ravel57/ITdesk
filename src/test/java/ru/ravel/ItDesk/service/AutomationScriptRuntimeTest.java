package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * CONDITION-выражения и аргументы ACTION-скриптов должны уметь читать {@code var.*} — переменные
 * автоматизации ({@link AutomationWorkflowValueService#setVariable}) — так же, как это уже умели
 * шаблоны {{ }} и HTTP/CREATE_TASK/SWITCH-ноды через {@link AutomationWorkflowValueService}.
 */
class AutomationScriptRuntimeTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private AutomationWorkflowValueService valueService;
	private AutomationScriptRuntime runtime;

	@BeforeEach
	void setUp() {
		valueService = new AutomationWorkflowValueService(mapper);
		runtime = new AutomationScriptRuntime(mapper, mock(AutomationActionExecutor.class), mock(AppSettingsService.class));
	}

	@Test
	void conditionReadsAutomationVariableSetElsewhereInTheWorkflow() {
		ObjectNode payload = mapper.createObjectNode();
		valueService.setVariable(payload, "duplicateTaskId", 7L);

		assertTrue(runtime.evaluateExpression("var.duplicateTaskId.notNull()", payload));
		assertFalse(runtime.evaluateExpression("var.duplicateTaskId.isNull()", payload));
	}

	@Test
	void conditionOnUnsetVariableIsFalseNotAnErrorOrTrue() {
		ObjectNode payload = mapper.createObjectNode();

		assertFalse(runtime.evaluateExpression("var.duplicateTaskId.notNull()", payload));
		assertTrue(runtime.evaluateExpression("var.duplicateTaskId.isNull()", payload));
	}

	@Test
	void bareBooleanVariableWorksDirectlyAsACondition() {
		ObjectNode payload = mapper.createObjectNode();
		valueService.setVariable(payload, "duplicateSameClient", true);

		assertTrue(runtime.evaluateExpression("var.duplicateSameClient", payload));

		valueService.setVariable(payload, "duplicateSameClient", false);
		assertFalse(runtime.evaluateExpression("var.duplicateSameClient", payload));
	}

	@Test
	void doubleAmpersandAndPipeAreSingleOperators() {
		ObjectNode payload = mapper.createObjectNode();
		valueService.setVariable(payload, "clients", 2);
		valueService.setVariable(payload, "status", "CRITICAL");

		assertTrue(runtime.evaluateExpression("var.clients >= 2 && var.clients < 10", payload));
		assertFalse(runtime.evaluateExpression("var.clients >= 3 && var.clients < 10", payload));
		assertTrue(runtime.evaluateExpression("var.clients >= 3 || var.clients < 10", payload));
		assertFalse(runtime.evaluateExpression("var.clients >= 3 || var.clients > 10", payload));
		assertTrue(runtime.evaluateExpression("var.clients >= 3 || (var.status = 'CRITICAL' && var.clients >= 2)", payload));
		assertTrue(runtime.evaluateExpression("var.clients >= 2 and var.status = 'CRITICAL'", payload));
	}

	@Test
	void ordinaryTopLevelPathsAreUnaffectedByTheVarPrefixHandling() {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 5);

		assertTrue(runtime.evaluateExpression("task.id = 5", payload));
		assertFalse(runtime.evaluateExpression("task.id = 6", payload));
	}
}

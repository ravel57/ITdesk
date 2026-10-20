package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockito.ArgumentCaptor;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.Event;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CONDITION-выражения и аргументы ACTION-скриптов должны уметь читать {@code var.*} — переменные
 * автоматизации ({@link AutomationWorkflowValueService#setVariable}) — так же, как это уже умели
 * шаблоны {{ }} и HTTP/CREATE_TASK/SWITCH-ноды через {@link AutomationWorkflowValueService}.
 */
class AutomationScriptRuntimeTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private AutomationWorkflowValueService valueService;
	private AutomationScriptRuntime runtime;
	private AutomationActionExecutor executor;

	@BeforeEach
	void setUp() {
		valueService = new AutomationWorkflowValueService(mapper);
		executor = mock(AutomationActionExecutor.class);
		runtime = new AutomationScriptRuntime(mapper, executor, mock(AppSettingsService.class), valueService);
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

	private AutomationExecutionContext context(ObjectNode payload) {
		return new AutomationExecutionContext(AutomationTrigger.builder().id(1L).name("rule").build(),
				Event.builder().payload(payload).build());
	}

	@Test
	void assignmentPutsTheStepResultIntoAVariableAndLaterCommandsReadIt() {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 9);
		when(executor.execute(eq("jev.findDuplicate"), any(), any())).thenReturn(Map.of("taskId", 7, "sameClient", true));

		runtime.executeActions("var.duplicate = jev.findDuplicate(); "
				+ "client.addInternalNote('Заявка #' + task.id + ' — дубль #' + var.duplicate.taskId)", context(payload));

		JsonNode duplicate = (JsonNode) valueService.getVariable(payload, "duplicate");
		assertEquals(7, duplicate.path("taskId").asInt());
		ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
		verify(executor).execute(eq("client.addInternalNote"), args.capture(), any());
		assertEquals("Заявка #9 — дубль #7", args.getValue().path("args").get(0).asText());
		assertTrue(runtime.evaluateExpression("var.duplicate.sameClient", payload));
		assertTrue(runtime.evaluateExpression("var.duplicate.taskId = 7", payload));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\n", "\r\n", "\r", ";", ";\n", "\n\n;\n"})
	void commandsAcceptNewlinesAndSemicolonsAndPreserveExecutionOrder(String separator) {
		ObjectNode payload = mapper.createObjectNode();
		when(executor.execute(eq("jev.findDuplicate"), any(), any())).thenReturn(Map.of("taskId", 7));

		runtime.executeActions(separator + "var.duplicate = jev.findDuplicate()" + separator
				+ "client.addInternalNote(var.duplicate.taskId)" + separator, context(payload));

		var order = org.mockito.Mockito.inOrder(executor);
		order.verify(executor).execute(eq("jev.findDuplicate"), any(), any());
		ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
		order.verify(executor).execute(eq("client.addInternalNote"), args.capture(), any());
		order.verifyNoMoreInteractions();
		assertEquals(7, args.getValue().path("args").get(0).asInt());
	}

	@Test
	void multilineCallKeepsItsArgumentsTogether() {
		ObjectNode payload = mapper.createObjectNode();
		runtime.executeActions("""
				var.label = 'заявка'
				client.addInternalNote(
				    var.label + ': ' +
				    'готово'
				)
				""", context(payload));

		ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
		verify(executor).execute(eq("client.addInternalNote"), args.capture(), any());
		assertEquals("заявка: готово", args.getValue().path("args").get(0).asText());
	}

	@Test
	void quotedNewlinesAndSemicolonsStayInsideTheMessage() {
		ObjectNode payload = mapper.createObjectNode();
		runtime.executeActions("client.addInternalNote('первая; строка\nвторая')\n"
				+ "client.sendMessage(\"ещё; строка\r\nследующая\")", context(payload));

		ArgumentCaptor<JsonNode> note = ArgumentCaptor.forClass(JsonNode.class);
		verify(executor).execute(eq("client.addInternalNote"), note.capture(), any());
		assertEquals("первая; строка\nвторая", note.getValue().path("args").get(0).asText());
		ArgumentCaptor<JsonNode> message = ArgumentCaptor.forClass(JsonNode.class);
		verify(executor).execute(eq("client.sendMessage"), message.capture(), any());
		assertEquals("ещё; строка\r\nследующая", message.getValue().path("args").get(0).asText());
	}

	@Test
	void escapedTrailingBackslashDoesNotHideTheNextCommand() {
		ObjectNode payload = mapper.createObjectNode();
		runtime.executeActions("client.addInternalNote('C:\\\\')\nclient.sendMessage('готово')", context(payload));

		verify(executor).execute(eq("client.sendMessage"), any(), any());
		ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
		verify(executor).execute(eq("client.addInternalNote"), args.capture(), any());
		assertEquals("C:\\", args.getValue().path("args").get(0).asText());
	}

	@Test
	void assignedStepResultCanBePassedAsAnArgumentToTheNextStep() {
		ObjectNode payload = mapper.createObjectNode();
		when(executor.execute(eq("incident.countSignals"), any(), any())).thenReturn(Map.of("clients", 3, "windowMinutes", 15));

		runtime.executeActions("var.window = incident.countSignals(5, 15); incident.notifyResponsible(5, var.window, 'текст')",
				context(payload));

		ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
		verify(executor).execute(eq("incident.notifyResponsible"), args.capture(), any());
		Object window = args.getValue().path("args").get(1) instanceof com.fasterxml.jackson.databind.node.POJONode pojo ? pojo.getPojo() : null;
		assertTrue(window instanceof JsonNode node && node.path("clients").asInt() == 3, String.valueOf(window));
	}

	@Test
	void stepWithoutResultLeavesTheVariableEmpty() {
		ObjectNode payload = mapper.createObjectNode();
		when(executor.execute(eq("jev.analyzeOutage"), any(), any())).thenReturn(null);

		runtime.executeActions("var.outage = jev.analyzeOutage()", context(payload));

		assertFalse(runtime.evaluateExpression("var.outage.notNull()", payload));
		assertFalse(runtime.evaluateExpression("var.outage.matchedIncidentId.notNull()", payload));
		assertTrue(runtime.evaluateExpression("var.outage.isNull()", payload));
	}

	@Test
	void assignmentAcceptsAPlainExpressionToo() {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 9);

		runtime.executeActions("var.label = 'заявка #' + task.id", context(payload));

		assertEquals("заявка #9", valueService.getVariable(payload, "label"));
	}

	@Test
	void ordinaryTopLevelPathsAreUnaffectedByTheVarPrefixHandling() {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 5);

		assertTrue(runtime.evaluateExpression("task.id = 5", payload));
		assertFalse(runtime.evaluateExpression("task.id = 6", payload));
	}
}

package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.SupportIncident;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Правило «Дубли заявок и инциденты (Jev)» из SQL-миграции V62: переменная {@code duplicate} задаётся в самом
 * графе ({@code var.duplicate = jev.findDuplicate()}), а привязка к инциденту и комментарии — ноды правила.
 */
class JevDuplicateRuleTest {

	private static final String MIGRATION = "V62__jev_duplicate_rule_explicit_variables.sql";

	private final ObjectMapper mapper = new ObjectMapper();
	private JevAssistService jevAssistService;
	private IncidentService incidentService;
	private ClientService clientService;
	private WorkflowRuleRunner runner;

	@BeforeEach
	void setUp() throws Exception {
		jevAssistService = mock(JevAssistService.class);
		incidentService = mock(IncidentService.class);
		clientService = mock(ClientService.class);
		JevService jevService = mock(JevService.class);
		when(jevService.isEnabled()).thenReturn(true);
		AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
		AutomationActionExecutor executor = WorkflowRuleRunner.executor(Map.of(
				JevService.class, jevService,
				JevAssistService.class, jevAssistService,
				IncidentService.class, incidentService,
				ClientService.class, clientService));
		AutomationScriptRuntime runtime = new AutomationScriptRuntime(mapper, executor, mock(AppSettingsService.class), values);
		runner = new WorkflowRuleRunner(runtime, values);
		when(incidentService.linkTasksForSystem(anyLong(), any())).thenReturn(List.of(9L));
	}

	@Test
	void noDuplicateDoesNothing() throws Exception {
		when(jevAssistService.findDuplicate(anyString(), eq(9L))).thenReturn(Optional.empty());
		run();
		verifyNoInteractions(incidentService, clientService);
	}

	@Test
	void duplicateOfIncidentTaskIsLinkedNotedAndReported() throws Exception {
		SupportIncident incident = SupportIncident.builder().id(5L).incidentNumber("INC-000005").build();
		when(jevAssistService.findDuplicate(anyString(), eq(9L)))
				.thenReturn(Optional.of(new JevAssistService.DuplicateMatch(7L, incident, false)));

		ObjectNode payload = run();

		verify(incidentService).linkTasksForSystem(5L, List.of(9L));
		verify(incidentService).addSystemNote(5L, "Заявка #9 — дубль заявки #7 (Jev)");
		ArgumentCaptor<Message> comment = ArgumentCaptor.forClass(Message.class);
		verify(clientService).sendMessageForSystem(eq(1L), comment.capture());
		assertTrue(comment.getValue().getIsComment(), "внутренний комментарий, клиент его не видит");
		assertEquals("Jev: заявка #9 — дубль заявки #7, привязана к инциденту INC-000005", comment.getValue().getText());
		assertEquals(7, payload.path("_automation").path("variables").path("duplicate").path("taskId").asInt());
	}

	@Test
	void duplicateOfOpenTaskOfTheSameClientOnlyComments() throws Exception {
		when(jevAssistService.findDuplicate(anyString(), eq(9L)))
				.thenReturn(Optional.of(new JevAssistService.DuplicateMatch(7L, null, true)));

		run();

		verify(incidentService, never()).linkTasksForSystem(anyLong(), any());
		ArgumentCaptor<Message> comment = ArgumentCaptor.forClass(Message.class);
		verify(clientService).sendMessageForSystem(eq(1L), comment.capture());
		assertTrue(comment.getValue().getText().contains("#9 похожа на открытую заявку #7 этого же клиента"), comment.getValue().getText());
	}

	@Test
	void duplicateOfAnotherClientMentionsPossibleIncident() throws Exception {
		when(jevAssistService.findDuplicate(anyString(), eq(9L)))
				.thenReturn(Optional.of(new JevAssistService.DuplicateMatch(7L, null, false)));

		run();

		ArgumentCaptor<Message> comment = ArgumentCaptor.forClass(Message.class);
		verify(clientService).sendMessageForSystem(eq(1L), comment.capture());
		assertTrue(comment.getValue().getText().contains("#7 другого клиента") && comment.getValue().getText().contains("инцидент"));
	}

	@Test
	void commentTextIsEditedInTheRuleNotInCode() throws Exception {
		when(jevAssistService.findDuplicate(anyString(), eq(9L)))
				.thenReturn(Optional.of(new JevAssistService.DuplicateMatch(7L, null, true)));
		String rule = WorkflowRuleRunner.edit(WorkflowRuleRunner.workflowOf(MIGRATION),
				"этого же клиента — возможно, это одна проблема и заявки стоит объединить.", "того же клиента — объедините заявки.");

		runner.run(rule, TriggerType.TASK_CREATED, "Дубли заявок и инциденты (Jev)", payload());

		ArgumentCaptor<Message> comment = ArgumentCaptor.forClass(Message.class);
		verify(clientService).sendMessageForSystem(eq(1L), comment.capture());
		assertEquals("Jev: заявка #9 похожа на открытую заявку #7 того же клиента — объедините заявки.", comment.getValue().getText());
	}

	@Test
	void migrationDescriptionFitsTheColumn() {
		assertTrue(WorkflowRuleRunner.descriptionOf(MIGRATION).length() <= 255);
	}

	private ObjectNode run() throws Exception {
		return runner.run(WorkflowRuleRunner.workflowOf(MIGRATION), TriggerType.TASK_CREATED, "Дубли заявок и инциденты (Jev)", payload());
	}

	private ObjectNode payload() {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 9);
		payload.putObject("client").put("id", 1);
		return payload;
	}
}

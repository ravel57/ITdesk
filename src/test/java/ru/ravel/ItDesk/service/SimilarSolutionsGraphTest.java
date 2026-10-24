package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.*;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/** Правило «Похожие решения из закрытых заявок (Jev)»: граф automation/templates/similar-solutions.json. */
class SimilarSolutionsGraphTest {

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
	private final AutomationActionExecutor actions = mock(AutomationActionExecutor.class);
	private final AutomationScriptRuntime expressions = new AutomationScriptRuntime(mapper, actions, mock(AppSettingsService.class), values);
	private final JevClient jev = mock(JevClient.class);
	private final LlmService llm = mock(LlmService.class);
	private final AutomationOperationTransactionService transactions = mock(AutomationOperationTransactionService.class);
	private final ClientRepository clients = mock(ClientRepository.class);
	private final TaskRepository tasks = mock(TaskRepository.class);
	private final AutomationWorkflowRunRepository runs = mock(AutomationWorkflowRunRepository.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final List<AutomationWorkflowRun> queue = new ArrayList<>();
	private AutomationOperationService operations;
	private AutomationWorkflowEngine engine;
	private AutomationTrigger trigger;
	private List<Map<String, Object>> found;
	private String choice;


	@BeforeEach
	void setUp() throws Exception {
		Map<Class<?>, Object> dependencies = Map.ofEntries(Map.entry(ObjectMapper.class, mapper), Map.entry(AutomationScriptRuntime.class, expressions),
				Map.entry(AutomationWorkflowValueService.class, values), Map.entry(AutomationActionExecutor.class, actions),
				Map.entry(JevClient.class, jev), Map.entry(LlmService.class, llm), Map.entry(AutomationOperationTransactionService.class, transactions),
				Map.entry(ClientRepository.class, clients), Map.entry(TaskRepository.class, tasks), Map.entry(JdbcTemplate.class, jdbc));
		var constructor = AutomationOperationService.class.getConstructors()[0];
		operations = (AutomationOperationService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
				.map(type -> dependencies.containsKey(type) ? dependencies.get(type) : mock(type)).toArray());
		engine = new AutomationWorkflowEngine(mapper, expressions, runs, mock(AutomationTriggerRepository.class), mock(AutomationPayloadService.class),
				values, mock(AutomationWorkflowNodeService.class), operations, mock(AutomationGraphHistoryService.class), mock(AfterCommitExecutor.class));
		try (var input = getClass().getResourceAsStream("/automation/templates/similar-solutions.json")) {
			trigger = AutomationTrigger.builder().id(31L).name("Похожие решения").triggerType(TriggerType.TASK_CREATED)
					.workflowDefinition(new String(input.readAllBytes(), StandardCharsets.UTF_8)).build();
		}

		ZonedDateTime created = ZonedDateTime.now().minusDays(10);
		Task solved = Task.builder().id(80L).name("Не подключается VPN").completed(true).createdAt(created).closedAt(created.plusHours(1))
				.statusChangeReason("Переустановили профиль").messages(new ArrayList<>()).build();
		Client other = Client.builder().id(5L).messages(new ArrayList<>(List.of(
				Message.builder().id(1L).date(created.plusMinutes(1)).text("VPN пишет ошибку 809").build(),
				Message.builder().id(2L).date(created.plusMinutes(5)).isSent(true).text("Удалите профиль VPN и создайте его заново").build()))).build();
		when(tasks.findById(80L)).thenReturn(Optional.of(solved));
		when(clients.findByTaskId(80L)).thenReturn(Optional.of(other));
		found = List.of(row(80L, "Не подключается VPN", "Ошибка 809", "Переустановили профиль"), row(74L, "Не работает 1С", "", ""));
		found.get(0).put("answer", "Удалите профиль VPN и создайте его заново");
		found.get(1).put("problem", "1С не запускается после обновления");
		// Тип Object явно: иначе any() выбирает перегрузку queryForList(String, Class, Object...)
		when(jdbc.queryForList(contains("to_tsquery"), ArgumentMatchers.<Object>any(), any(), any(), any(), any(), any())).thenAnswer(call -> found);
		when(transactions.read(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
		Map<String, JsonNode> receipts = new HashMap<>();
		when(transactions.once(anyString(), anyString(), any()))
				.thenAnswer(call -> receipts.computeIfAbsent(call.getArgument(0), key -> ((Supplier<JsonNode>) call.getArgument(2)).get()));
		Set<String> claims = new HashSet<>();
		when(jdbc.update(startsWith("insert into automation_graph_memory"), anyString())).thenAnswer(call -> claims.add(call.getArgument(1)) ? 1 : 0);
		when(jdbc.update(startsWith("delete from automation_graph_memory"), anyString())).thenAnswer(call -> claims.remove(call.getArgument(1)) ? 1 : 0);
		choice = "80";
		when(jev.evaluate(anyString(), any(), any()))
				.thenAnswer(call -> Optional.of(mapper.readTree("{\"decision\":{\"choice\":\"" + choice + "\",\"confidence\":0.9}}")));
		when(llm.generate(anyString())).thenReturn("В прошлый раз помогло пересоздать профиль VPN. Предложите клиенту удалить профиль и создать заново.");
		when(runs.save(any())).thenAnswer(call -> {
			queue.add(call.getArgument(0));
			return call.getArgument(0);
		});
	}


	@Test
	void templateIsAValidTransparentGraph() throws Exception {
		assertThat(engine.validateAsMap(trigger.getWorkflowDefinition())).containsEntry("valid", true);
		assertThat(mapper.readTree(trigger.getWorkflowDefinition()).path("nodes").get(1).path("type").asText()).isEqualTo("PARAMETERS");
	}


	@Test
	void similarClosedTaskBecomesHintForOperator() throws Exception {
		assertThat(engine.execute(trigger, event(1L)).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();

		ArgumentCaptor<Map<String, Object>> questions = ArgumentCaptor.forClass(Map.class);
		verify(jev).evaluate(anyString(), eq("VPN не работает. Ошибка 809 при подключении"), questions.capture());
		assertThat(questions.getValue().toString()).contains(
				"Не подключается VPN. Ошибка 809 Ответ оператора: Удалите профиль VPN и создайте его заново (закрыта: Переустановили профиль)",
				"Не работает 1С. 1С не запускается после обновления");
		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llm).generate(prompt.capture());
		assertThat(prompt.getValue()).contains("№80", "клиент: VPN пишет ошибку 809", "оператор: Удалите профиль VPN", "Переустановили профиль");
		ArgumentCaptor<JsonNode> note = ArgumentCaptor.forClass(JsonNode.class);
		verify(actions).execute(eq("client.addInternalNote"), note.capture(), any());
		assertThat(note.getValue().path("args").get(0).asText()).contains("№80", "«Не подключается VPN»", "пересоздать профиль VPN");
	}


	@Test
	void noMatchingTaskMeansNoHint() throws Exception {
		choice = "__none__";
		assertThat(engine.execute(trigger, event(1L)).completed()).isTrue();
		verifyNoInteractions(llm, actions);
	}


	@Test
	void noCandidatesMeansNoJevCall() throws Exception {
		found = List.of();
		assertThat(engine.execute(trigger, event(1L)).completed()).isTrue();
		verifyNoInteractions(jev, llm, actions);
	}


	@Test
	void repeatedEventForSameTaskGivesOneHint() throws Exception {
		assertThat(engine.execute(trigger, event(1L)).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();
		assertThat(engine.execute(trigger, event(2L)).completed()).isTrue();
		verify(llm, times(1)).generate(anyString());
	}


	@Test
	void searchExcludesCurrentTaskAndFiltersOrganizationOnlyWhenAsked() {
		var context = new AutomationExecutionContext(trigger, Event.builder().payload(mapper.createObjectNode()).build());
		operations.execute("data.similarTasks", Map.of("text", "VPN не работает", "excludeTaskId", 100L, "organizationId", 9L, "limit", 5),
				context, false, "read-1", null);
		verify(jdbc).queryForList(contains("to_tsquery"), eq("VPN не работает"), eq(100L), eq(365), isNull(), isNull(), eq(5));

		operations.execute("data.similarTasks", Map.of("text", "VPN не работает", "sameOrganizationOnly", true, "organizationId", 9L, "days", 30),
				context, false, "read-2", null);
		verify(jdbc).queryForList(contains("to_tsquery"), eq("VPN не работает"), isNull(), eq(30), eq(9L), eq(9L), eq(100));

		JsonNode none = operations.execute("data.similarTasks", Map.of("text", "VPN", "sameOrganizationOnly", true),
				context, false, "read-3", null);
		assertThat(none).isEmpty();
	}


	private Event event(long id) {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 100L).put("name", "VPN не работает").put("description", "Ошибка 809 при подключении");
		payload.putObject("client").put("id", 7L);
		return Event.builder().id(id).triggerType(TriggerType.TASK_CREATED).payload(payload).build();
	}


	private static Map<String, Object> row(Long id, String name, String description, String closeReason) {
		Map<String, Object> row = new HashMap<>();
		row.put("id", id);
		row.put("name", name);
		row.put("description", description);
		row.put("close_reason", closeReason);
		row.put("closed_at", null);
		row.put("client_id", 5L);
		row.put("rank", 0.1);
		return row;
	}

}

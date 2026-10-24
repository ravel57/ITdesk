package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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


/** Правило «Черновик статьи из закрытой заявки (Jev)»: граф automation/templates/knowledge-draft.json. */
class KnowledgeDraftGraphTest {

	private static final String ARTICLE = """
			ЗАГОЛОВОК: VPN не подключается после смены пароля
			ПРОБЛЕМА: После смены пароля VPN-клиент выдаёт ошибку авторизации.
			РЕШЕНИЕ:
			1. Удалите сохранённый профиль VPN.
			2. Создайте профиль заново и введите новый пароль.
			""";

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
	private final KnowledgeService knowledge = mock(KnowledgeService.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final List<AutomationWorkflowRun> queue = new ArrayList<>();
	private AutomationOperationService operations;
	private AutomationWorkflowEngine engine;
	private AutomationTrigger trigger;
	private Task task;
	private Client client;
	private double reusable;
	private String coveredBy;


	@BeforeEach
	void setUp() throws Exception {
		Map<Class<?>, Object> dependencies = Map.ofEntries(Map.entry(ObjectMapper.class, mapper), Map.entry(AutomationScriptRuntime.class, expressions),
				Map.entry(AutomationWorkflowValueService.class, values), Map.entry(AutomationActionExecutor.class, actions),
				Map.entry(JevClient.class, jev), Map.entry(LlmService.class, llm), Map.entry(AutomationOperationTransactionService.class, transactions),
				Map.entry(ClientRepository.class, clients), Map.entry(TaskRepository.class, tasks), Map.entry(KnowledgeService.class, knowledge),
				Map.entry(JdbcTemplate.class, jdbc));
		var constructor = AutomationOperationService.class.getConstructors()[0];
		operations = (AutomationOperationService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
				.map(type -> dependencies.containsKey(type) ? dependencies.get(type) : mock(type)).toArray());
		engine = new AutomationWorkflowEngine(mapper, expressions, runs, mock(AutomationTriggerRepository.class), mock(AutomationPayloadService.class),
				values, mock(AutomationWorkflowNodeService.class), operations, mock(AutomationGraphHistoryService.class), mock(AfterCommitExecutor.class));
		try (var input = getClass().getResourceAsStream("/automation/templates/knowledge-draft.json")) {
			trigger = AutomationTrigger.builder().id(30L).name("Черновик статьи").triggerType(TriggerType.TASK_CLOSED)
					.workflowDefinition(new String(input.readAllBytes(), StandardCharsets.UTF_8)).build();
		}

		ZonedDateTime created = ZonedDateTime.now().minusHours(3);
		task = Task.builder().id(100L).name("VPN не работает").completed(true).createdAt(created).closedAt(created.plusHours(2))
				.statusChangeReason("Решено").messages(new ArrayList<>()).build();
		client = Client.builder().id(7L).messages(new ArrayList<>(List.of(
				message(1L, created.minusDays(3), false, false, "Старый вопрос про принтер"),
				message(2L, created.minusMinutes(5), false, false, "После смены пароля не подключается VPN"),
				message(3L, created.plusMinutes(10), true, false, "Удалите профиль VPN и создайте заново"),
				message(4L, created.plusMinutes(20), true, true, "Типовая проблема после смены пароля"),
				systemMessage(5L, created.plusMinutes(1), "Здравствуйте! Это автоответ."),
				message(6L, created.plusMinutes(40), false, false, "Заработало, спасибо")))).build();
		when(tasks.findById(100L)).thenAnswer(call -> Optional.of(task));
		when(clients.findByTaskId(100L)).thenAnswer(call -> Optional.of(client));
		when(transactions.read(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
		Map<String, JsonNode> receipts = new HashMap<>();
		when(transactions.once(anyString(), anyString(), any()))
				.thenAnswer(call -> receipts.computeIfAbsent(call.getArgument(0), key -> ((Supplier<JsonNode>) call.getArgument(2)).get()));
		Set<String> claims = new HashSet<>();
		when(jdbc.update(startsWith("insert into automation_graph_memory"), anyString())).thenAnswer(call -> claims.add(call.getArgument(1)) ? 1 : 0);
		when(jdbc.update(startsWith("delete from automation_graph_memory"), anyString())).thenAnswer(call -> claims.remove(call.getArgument(1)) ? 1 : 0);
		when(knowledge.getKnowledgeBase()).thenReturn(List.of(Knowledge.builder().id(9L).title("Принтер").texts(List.of("Перезагрузите принтер")).build()));
		when(knowledge.createDraft(anyString(), anyList(), eq(100L)))
				.thenAnswer(call -> Knowledge.builder().id(50L).title(call.getArgument(0)).status(KnowledgeStatus.DRAFT).sourceTaskId(100L).build());
		reusable = 0.95;
		coveredBy = "__none__";
		when(jev.evaluate(anyString(), any(), any())).thenAnswer(call -> {
			Map<String, Object> questions = call.getArgument(2);
			Map<?, ?> decision = (Map<?, ?>) questions.get("decision");
			return Optional.of(mapper.readTree("noul".equals(decision.get("type"))
					? "{\"decision\":{\"noul\":" + reusable + "}}"
					: "{\"decision\":{\"choice\":\"" + coveredBy + "\",\"confidence\":0.9}}"));
		});
		when(llm.generate(anyString())).thenReturn(ARTICLE);
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
	void closedTaskWithReusableSolutionBecomesDraftAndOperatorIsNotified() throws Exception {
		assertThat(engine.execute(trigger, event()).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();

		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llm).generate(prompt.capture());
		assertThat(prompt.getValue())
				.contains("VPN не работает", "Решено", "клиент: После смены пароля", "оператор: Удалите профиль",
						"комментарий оператора: Типовая проблема", "клиент: Заработало")
				.doesNotContain("принтер", "автоответ");
		verify(knowledge).createDraft(eq("VPN не подключается после смены пароля"),
				eq(List.of("Проблема:\nПосле смены пароля VPN-клиент выдаёт ошибку авторизации.",
						"Решение:\n1. Удалите сохранённый профиль VPN.\n2. Создайте профиль заново и введите новый пароль.")), eq(100L));
		ArgumentCaptor<JsonNode> note = ArgumentCaptor.forClass(JsonNode.class);
		verify(actions).execute(eq("client.addInternalNote"), note.capture(), any());
		assertThat(note.getValue().path("args").get(0).asText()).contains("№100", "#50", "VPN не подключается после смены пароля");
	}


	@Test
	void noReusableSolutionStopsBeforeAi() throws Exception {
		reusable = 0.2;
		assertThat(engine.execute(trigger, event()).completed()).isTrue();
		verifyNoInteractions(llm, actions);
		verify(knowledge, never()).createDraft(anyString(), anyList(), anyLong());
	}


	@Test
	void solutionAlreadyDescribedInArticleStopsBeforeAi() throws Exception {
		coveredBy = "9";
		assertThat(engine.execute(trigger, event()).completed()).isTrue();
		verifyNoInteractions(llm, actions);
	}


	@Test
	void taskWithoutOperatorRepliesIsSkippedWithoutJev() throws Exception {
		client.getMessages().removeIf(message -> Boolean.TRUE.equals(message.getIsSent()));
		assertThat(engine.execute(trigger, event()).completed()).isTrue();
		verifyNoInteractions(jev, llm, actions);
	}


	@Test
	void aiAnswerNoCreatesNoDraftAndNoNote() throws Exception {
		when(llm.generate(anyString())).thenReturn("НЕТ");
		assertThat(engine.execute(trigger, event()).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();
		verify(knowledge, never()).createDraft(anyString(), anyList(), anyLong());
		verifyNoInteractions(actions);
	}


	@Test
	void reopenedAndClosedAgainTaskGetsNoSecondDraft() throws Exception {
		assertThat(engine.execute(trigger, event()).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();
		assertThat(engine.execute(trigger, event(2L)).completed()).isTrue();
		verify(llm, times(1)).generate(anyString());
		verify(knowledge, times(1)).createDraft(anyString(), anyList(), eq(100L));
	}


	@Test
	void conversationKeepsBeginningAndEndOfLongDialog() {
		for (long id = 10; id < 40; id++) {
			client.getMessages().add(message(id, task.getCreatedAt().plusMinutes(41 + id), id % 2 == 0, false, "реплика " + id));
		}
		JsonNode result = operations.execute("data.taskConversation", Map.of("taskId", 100L, "limit", 8),
				new AutomationExecutionContext(trigger, Event.builder().payload(mapper.createObjectNode()).build()), false, "read", null);
		List<String> lines = result.path("text").asText().lines().toList();
		assertThat(lines).hasSize(9).contains("…");
		assertThat(lines.getFirst()).isEqualTo("клиент: После смены пароля не подключается VPN");
		assertThat(lines.getLast()).isEqualTo("клиент: реплика 39");
		assertThat(result.path("operatorReplies").asLong()).isEqualTo(17);
		assertThat(result.path("closeReason").asText()).isEqualTo("Решено");
	}


	private Event event() {
		return event(1L);
	}


	private Event event(long id) {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("task").put("id", 100L).put("name", "VPN не работает");
		payload.putObject("client").put("id", 7L);
		return Event.builder().id(id).triggerType(TriggerType.TASK_CLOSED).payload(payload).build();
	}


	private static Message message(Long id, ZonedDateTime date, boolean sent, boolean comment, String text) {
		return Message.builder().id(id).date(date).isSent(sent).isComment(comment).text(text).build();
	}


	private static Message systemMessage(Long id, ZonedDateTime date, String text) {
		Message message = message(id, date, true, false, text);
		message.setUser(new SystemUser());
		return message;
	}

}

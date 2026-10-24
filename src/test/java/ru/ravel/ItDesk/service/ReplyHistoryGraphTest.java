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
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


/** Правило «Подсказка ответа по истории чатов (Jev)»: граф automation/templates/reply-history.json. */
class ReplyHistoryGraphTest {

	private static final String ANSWER = "Не добавлен корневой сертификат УЦ. Скачайте файл, установите его и перезапустите КриптоПРО";

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
	private final AutomationActionExecutor actions = mock(AutomationActionExecutor.class);
	private final AutomationScriptRuntime expressions = new AutomationScriptRuntime(mapper, actions, mock(AppSettingsService.class), values);
	private final JevClient jev = mock(JevClient.class);
	private final LlmService llm = mock(LlmService.class);
	private final AutomationOperationTransactionService transactions = mock(AutomationOperationTransactionService.class);
	private final ClientRepository clients = mock(ClientRepository.class);
	private final AutomationWorkflowRunRepository runs = mock(AutomationWorkflowRunRepository.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final List<AutomationWorkflowRun> queue = new ArrayList<>();
	private final Set<String> claims = new HashSet<>();
	private AutomationOperationService operations;
	private AutomationWorkflowEngine engine;
	private AutomationTrigger trigger;
	private Client client;
	private Message source;
	private List<Map<String, Object>> found;
	private String choice;


	@BeforeEach
	void setUp() throws Exception {
		Map<Class<?>, Object> dependencies = Map.ofEntries(Map.entry(ObjectMapper.class, mapper), Map.entry(AutomationScriptRuntime.class, expressions),
				Map.entry(AutomationWorkflowValueService.class, values), Map.entry(AutomationActionExecutor.class, actions),
				Map.entry(JevClient.class, jev), Map.entry(LlmService.class, llm), Map.entry(AutomationOperationTransactionService.class, transactions),
				Map.entry(ClientRepository.class, clients), Map.entry(JdbcTemplate.class, jdbc));
		var constructor = AutomationOperationService.class.getConstructors()[0];
		operations = (AutomationOperationService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
				.map(type -> dependencies.containsKey(type) ? dependencies.get(type) : mock(type)).toArray());
		engine = new AutomationWorkflowEngine(mapper, expressions, runs, mock(AutomationTriggerRepository.class), mock(AutomationPayloadService.class),
				values, mock(AutomationWorkflowNodeService.class), operations, mock(AutomationGraphHistoryService.class), mock(AfterCommitExecutor.class));
		try (var input = getClass().getResourceAsStream("/automation/templates/reply-history.json")) {
			trigger = AutomationTrigger.builder().id(32L).name("Подсказка по истории").triggerType(TriggerType.MESSAGE_INCOMING)
					.workflowDefinition(new String(input.readAllBytes(), StandardCharsets.UTF_8)).build();
		}

		source = Message.builder().id(200L).text("ЭЦП перестала работать, что делать?").build();
		client = Client.builder().id(7L).messages(new ArrayList<>(List.of(source))).build();
		when(clients.findById(7L)).thenAnswer(call -> Optional.of(client));
		found = new ArrayList<>(List.of(
				row(281L, 278L, "Добрый день! У меня не работает ЭЦП", ANSWER),
				row(281L, 277L, "ЭЦП не работает", ANSWER),
				row(300L, 299L, "Не работает 1С", "Перезапустите службу 1С на сервере и попробуйте войти снова")));
		// Тип Object явно: иначе any() выбирает перегрузку queryForList(String, Class, Object...)
		when(jdbc.queryForList(contains("questions as"), ArgumentMatchers.<Object>any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(call -> found);
		when(transactions.read(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
		Map<String, JsonNode> receipts = new HashMap<>();
		when(transactions.once(anyString(), anyString(), any()))
				.thenAnswer(call -> receipts.computeIfAbsent(call.getArgument(0), key -> ((Supplier<JsonNode>) call.getArgument(2)).get()));
		when(jdbc.update(startsWith("insert into automation_graph_memory"), anyString())).thenAnswer(call -> claims.add(call.getArgument(1)) ? 1 : 0);
		when(jdbc.update(startsWith("delete from automation_graph_memory"), anyString())).thenAnswer(call -> claims.remove(call.getArgument(1)) ? 1 : 0);
		choice = "281";
		when(jev.evaluate(anyString(), any(), any()))
				.thenAnswer(call -> Optional.of(mapper.readTree("{\"decision\":{\"choice\":\"" + choice + "\",\"confidence\":0.9}}")));
		when(llm.generate(anyString())).thenReturn("Похоже, не установлен корневой сертификат УЦ. Скачайте файл сертификата и перезапустите КриптоПРО.");
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
	void pastOperatorAnswerBecomesReplyHint() throws Exception {
		assertThat(engine.execute(trigger, event(1L)).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();

		ArgumentCaptor<Map<String, Object>> questions = ArgumentCaptor.forClass(Map.class);
		verify(jev).evaluate(anyString(), eq(source.getText()), questions.capture());
		assertThat(questions.getValue().toString())
				.contains("Вопрос: Добрый день! У меня не работает ЭЦП Ответ оператора: Не добавлен корневой сертификат УЦ")
				.doesNotContain("Вопрос: ЭЦП не работает");
		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llm).generate(prompt.capture());
		assertThat(prompt.getValue()).contains(source.getText(), "Добрый день! У меня не работает ЭЦП", ANSWER);
		ArgumentCaptor<JsonNode> note = ArgumentCaptor.forClass(JsonNode.class);
		verify(actions).execute(eq("client.addInternalNote"), note.capture(), any());
		assertThat(note.getValue().path("args").get(0).asText()).contains("#200", "истории чатов", "корневой сертификат");
	}


	@Test
	void knowledgeBaseHintForSameMessageSuppressesHistoryHint() throws Exception {
		claims.add("knowledge-message:7:200");
		assertThat(engine.execute(trigger, event(1L)).completed()).isTrue();
		verifyNoInteractions(llm, actions);
	}


	@Test
	void noFittingAnswerMeansNoHint() throws Exception {
		choice = "__none__";
		assertThat(engine.execute(trigger, event(1L)).completed()).isTrue();
		verifyNoInteractions(llm, actions);
	}


	@Test
	void olderMessageIsNotAnalyzedAfterNewerIncoming() throws Exception {
		client.getMessages().add(Message.builder().id(201L).text("Уже не надо, разобрался").build());
		assertThat(engine.execute(trigger, event(1L)).completed()).isTrue();
		verifyNoInteractions(jev, llm, actions);
	}


	@Test
	void newerMessageWhileGeneratingSuppressesObsoleteHint() throws Exception {
		assertThat(engine.execute(trigger, event(1L)).scheduled()).isTrue();
		when(llm.generate(anyString())).thenAnswer(call -> {
			client.getMessages().add(Message.builder().id(201L).text("Уже не надо").build());
			return "Устаревшая подсказка";
		});
		assertThat(engine.resume(trigger, queue.getFirst()).completed()).isTrue();
		verifyNoInteractions(actions);
	}


	@Test
	void searchExcludesCurrentClientAndKeepsOneQuestionPerAnswer() {
		var context = new AutomationExecutionContext(trigger, Event.builder().payload(mapper.createObjectNode()).build());
		JsonNode result = operations.execute("data.similarReplies",
				Map.of("text", "ЭЦП не работает", "excludeClientId", 7L, "organizationId", 9L, "limit", 5), context, false, "read-1", null);
		verify(jdbc).queryForList(contains("questions as"), eq("ЭЦП не работает"), eq(7L), eq(365), isNull(), isNull(), eq(15), eq(40));
		assertThat(result).hasSize(2);
		assertThat(result.get(0).path("id").asLong()).isEqualTo(281L);
		assertThat(result.get(0).path("questionId").asLong()).isEqualTo(278L);

		operations.execute("data.similarReplies", Map.of("text", "ЭЦП", "sameOrganizationOnly", true, "organizationId", 9L, "minAnswerLength", 80),
				context, false, "read-2", null);
		verify(jdbc).queryForList(contains("questions as"), eq("ЭЦП"), isNull(), eq(365), eq(9L), eq(9L), eq(300), eq(80));
	}


	private Event event(long id) throws Exception {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("client").put("id", 7L);
		payload.set("message", mapper.valueToTree(source));
		return Event.builder().id(id).triggerType(TriggerType.MESSAGE_INCOMING).payload(payload).build();
	}


	private static Map<String, Object> row(Long answerId, Long questionId, String question, String answer) {
		Map<String, Object> row = new HashMap<>();
		row.put("answer_id", answerId);
		row.put("question_id", questionId);
		row.put("client_id", 5L);
		row.put("question", question);
		row.put("answer", answer);
		row.put("answer_date", null);
		row.put("rank", 0.1);
		return row;
	}

}

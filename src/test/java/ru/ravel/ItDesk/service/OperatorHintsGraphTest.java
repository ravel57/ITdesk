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


/** Правило «Подсказка оператору (Jev)»: граф automation/templates/operator-hints.json. */
class OperatorHintsGraphTest {

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
	private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
	private final AutomationActionExecutor actions = mock(AutomationActionExecutor.class);
	private final AutomationScriptRuntime expressions = new AutomationScriptRuntime(mapper, actions, mock(AppSettingsService.class), values);
	private final JevClient jev = mock(JevClient.class);
	private final LlmService llm = mock(LlmService.class);
	private final AutomationOperationTransactionService transactions = mock(AutomationOperationTransactionService.class);
	private final ClientRepository clients = mock(ClientRepository.class);
	private final TaskRepository tasks = mock(TaskRepository.class);
	private final KnowledgeService knowledge = mock(KnowledgeService.class);
	private final OperatorHintService hints = mock(OperatorHintService.class);
	private final AutomationWorkflowRunRepository runs = mock(AutomationWorkflowRunRepository.class);
	private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
	private final List<AutomationWorkflowRun> queue = new ArrayList<>();
	/** automation_graph_memory: ключ → когда занят. */
	private final Map<String, ZonedDateTime> memory = new HashMap<>();
	private AutomationOperationService operations;
	private AutomationWorkflowEngine engine;
	private AutomationTrigger trigger;
	private Client client;
	private Message source;
	private String choice;


	@BeforeEach
	void setUp() throws Exception {
		Map<Class<?>, Object> dependencies = Map.ofEntries(Map.entry(ObjectMapper.class, mapper), Map.entry(AutomationScriptRuntime.class, expressions),
				Map.entry(AutomationWorkflowValueService.class, values), Map.entry(AutomationActionExecutor.class, actions),
				Map.entry(JevClient.class, jev), Map.entry(LlmService.class, llm), Map.entry(AutomationOperationTransactionService.class, transactions),
				Map.entry(ClientRepository.class, clients), Map.entry(TaskRepository.class, tasks), Map.entry(KnowledgeService.class, knowledge),
				Map.entry(OperatorHintService.class, hints), Map.entry(JdbcTemplate.class, jdbc));
		var constructor = AutomationOperationService.class.getConstructors()[0];
		operations = (AutomationOperationService) constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
				.map(type -> dependencies.containsKey(type) ? dependencies.get(type) : mock(type)).toArray());
		engine = new AutomationWorkflowEngine(mapper, expressions, runs, mock(AutomationTriggerRepository.class), mock(AutomationPayloadService.class),
				values, mock(AutomationWorkflowNodeService.class), operations, mock(AutomationGraphHistoryService.class), mock(AfterCommitExecutor.class));
		trigger = trigger(Map.of());

		source = Message.builder().id(200L).text("ЭЦП перестала работать, при подписании ошибка").build();
		client = Client.builder().id(7L).messages(new ArrayList<>(List.of(source))).build();
		when(clients.findById(7L)).thenAnswer(call -> Optional.of(client));
		when(knowledge.getKnowledgeBase()).thenReturn(List.of(
				Knowledge.builder().id(9L).title("Установка сертификатов УЦ").texts(List.of("Скачайте корневой сертификат и установите его")).build()));

		ZonedDateTime created = ZonedDateTime.now().minusDays(3);
		Task solved = Task.builder().id(92L).name("Не работает ЭЦП").completed(true).createdAt(created).closedAt(created.plusHours(1))
				.statusChangeReason("Решено").messages(new ArrayList<>()).build();
		Client other = Client.builder().id(5L).messages(new ArrayList<>(List.of(
				Message.builder().id(1L).date(created.plusMinutes(1)).text("У меня не работает ЭЦП").build(),
				Message.builder().id(2L).date(created.plusMinutes(5)).isSent(true).text("Не добавлен корневой сертификат УЦ, установите его").build()))).build();
		when(tasks.findById(92L)).thenReturn(Optional.of(solved));
		when(clients.findByTaskId(92L)).thenReturn(Optional.of(other));
		// Тип Object явно: иначе any() выбирает перегрузку queryForList(String, Class, Object...)
		when(jdbc.queryForList(contains("from task t"), ArgumentMatchers.<Object>any(), any(), any(), any(), any(), any()))
				.thenAnswer(call -> List.of(row("id", 92L, "name", "Не работает ЭЦП", "description", "", "close_reason", "Решено",
						"client_id", 5L, "problem", "У меня не работает ЭЦП", "answer", "Не добавлен корневой сертификат УЦ, установите его")));
		when(jdbc.queryForList(contains("questions as"), ArgumentMatchers.<Object>any(), any(), any(), any(), any(), any(), any()))
				.thenAnswer(call -> List.of(row("answer_id", 281L, "question_id", 278L, "client_id", 5L, "question", "Добрый день! Не работает ЭЦП",
						"answer", "Не добавлен корневой сертификат УЦ. Скачайте этот файл и перезапустите КриптоПРО")));

		when(transactions.read(any())).thenAnswer(call -> ((Supplier<?>) call.getArgument(0)).get());
		Map<String, JsonNode> receipts = new HashMap<>();
		when(transactions.once(anyString(), anyString(), any()))
				.thenAnswer(call -> receipts.computeIfAbsent(call.getArgument(0), key -> ((Supplier<JsonNode>) call.getArgument(2)).get()));
		when(jdbc.update(startsWith("insert into automation_graph_memory(memory_key) values"), anyString()))
				.thenAnswer(call -> memory.putIfAbsent(call.getArgument(1), ZonedDateTime.now()) == null ? 1 : 0);
		when(jdbc.update(startsWith("delete from automation_graph_memory"), anyString()))
				.thenAnswer(call -> memory.remove(call.getArgument(1)) != null ? 1 : 0);
		when(jdbc.update(contains("on conflict (memory_key) do update"), anyString(), any())).thenAnswer(call -> {
			ZonedDateTime taken = memory.get(call.getArgument(1));
			int minutes = ((Number) call.getArgument(2)).intValue();
			if (taken != null && taken.isAfter(ZonedDateTime.now().minusMinutes(minutes))) return 0;
			memory.put(call.getArgument(1), ZonedDateTime.now());
			return 1;
		});
		when(jdbc.queryForObject(contains("from automation_graph_memory"), eq(Integer.class), anyString(), any())).thenAnswer(call -> {
			ZonedDateTime taken = memory.get(call.getArgument(2));
			int minutes = ((Number) call.getArgument(3)).intValue();
			return taken != null && taken.isAfter(ZonedDateTime.now().minusMinutes(minutes)) ? 1 : 0;
		});

		choice = "article:9";
		when(jev.evaluate(anyString(), any(), any()))
				.thenAnswer(call -> Optional.of(mapper.readTree("{\"decision\":{\"choice\":\"" + choice + "\",\"confidence\":0.9}}")));
		when(llm.generate(anyString())).thenReturn("Предложите клиенту установить корневой сертификат УЦ.");
		when(hints.publish(anyLong(), any(), anyString(), any(), any(), any(), any(), any(), anyString()))
				.thenAnswer(call -> OperatorHint.builder().id(70L).noteMessageId(300L).build());
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
	void allSourcesAreComparedInOneJevChoice() throws Exception {
		run();
		ArgumentCaptor<Map<String, Object>> questions = ArgumentCaptor.forClass(Map.class);
		verify(jev, times(1)).evaluate(anyString(), eq(source.getText()), questions.capture());
		String criteria = questions.getValue().toString();
		assertThat(criteria).contains("article:9=Статья базы знаний «Установка сертификатов УЦ»",
				"task:92=Закрытая заявка: Не работает ЭЦП. У меня не работает ЭЦП Ответ оператора: Не добавлен корневой сертификат",
				"reply:281=Ответ оператора на похожий вопрос другого клиента. Вопрос: Добрый день! Не работает ЭЦП",
				"__none__=Ничего не подходит");
		verify(llm, times(1)).generate(anyString());
	}


	@Test
	void articleHint() throws Exception {
		run();
		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llm).generate(prompt.capture());
		assertThat(prompt.getValue()).contains("статья базы знаний «Установка сертификатов УЦ»", "Скачайте корневой сертификат");
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(hints).publish(eq(7L), eq(200L), eq("article"), eq("Статьи базы знаний"), eq(9L), eq("Установка сертификатов УЦ"), eq(0.9),
				argThat(rule -> rule.getId() == 33L), text.capture());
		assertThat(text.getValue()).startsWith("Jev: к сообщению #200 подходит статья базы знаний «Установка сертификатов УЦ».\n")
				.endsWith("Предложите клиенту установить корневой сертификат УЦ.");
	}


	@Test
	void closedTaskHintUsesTaskConversation() throws Exception {
		choice = "task:92";
		run();
		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llm).generate(prompt.capture());
		assertThat(prompt.getValue()).contains("закрытая заявка №92 «Не работает ЭЦП»", "«Решено»",
				"клиент: У меня не работает ЭЦП", "оператор: Не добавлен корневой сертификат УЦ");
		ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
		verify(hints).publish(eq(7L), eq(200L), eq("task"), eq("Закрытые заявки"), eq(92L), eq("Не работает ЭЦП"), any(), any(), text.capture());
		assertThat(text.getValue()).startsWith("Jev: похожая проблема уже решалась в заявке №92 «Не работает ЭЦП».");
	}


	@Test
	void chatHistoryHintRemindsAboutAttachments() throws Exception {
		choice = "reply:281";
		run();
		ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
		verify(llm).generate(prompt.capture());
		assertThat(prompt.getValue()).contains("Ответ оператора на него: «Не добавлен корневой сертификат УЦ. Скачайте этот файл", "приложить их");
		verify(hints).publish(eq(7L), eq(200L), eq("reply"), eq("История чатов"), eq(281L), eq("Добрый день! Не работает ЭЦП"), any(), any(),
				startsWith("Jev: вариант ответа на сообщение #200 по похожему обращению из истории чатов."));
	}


	@Test
	void nothingFitsMeansNoHint() throws Exception {
		choice = "__none__";
		assertThat(engine.execute(trigger, event(1L, source)).completed()).isTrue();
		verifyNoInteractions(llm, hints);
		assertThat(memory).isEmpty();
	}


	@Test
	void clientGetsAtMostOneHintPerInterval() throws Exception {
		run();
		Message next = Message.builder().id(201L).text("И ещё не работает 1С").build();
		client.getMessages().add(next);
		assertThat(engine.execute(trigger, event(2L, next)).completed()).isTrue();
		verify(jev, times(1)).evaluate(anyString(), any(), any());
		verify(hints, times(1)).publish(anyLong(), any(), anyString(), any(), any(), any(), any(), any(), anyString());
	}


	@Test
	void zeroCooldownAllowsHintForEveryMessage() throws Exception {
		trigger = trigger(Map.of("cooldownMinutes", 0));
		run();
		Message next = Message.builder().id(201L).text("И ещё не работает ЭЦП на втором компьютере").build();
		client.getMessages().add(next);
		assertThat(engine.execute(trigger, event(2L, next)).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.get(1)).completed()).isTrue();
		verify(hints, times(2)).publish(anyLong(), any(), anyString(), any(), any(), any(), any(), any(), anyString());
	}


	@Test
	void disabledSourcesAreNotQueried() throws Exception {
		trigger = trigger(Map.of("useArticles", false, "useReplies", false));
		choice = "task:92";
		run();
		verify(knowledge, never()).getKnowledgeBase();
		verify(jdbc, never()).queryForList(contains("questions as"), ArgumentMatchers.<Object>any(), any(), any(), any(), any(), any(), any());
		ArgumentCaptor<Map<String, Object>> questions = ArgumentCaptor.forClass(Map.class);
		verify(jev).evaluate(anyString(), any(), questions.capture());
		assertThat(questions.getValue().toString()).contains("task:92").doesNotContain("article:9", "reply:281");
	}


	@Test
	void aiFailureReleasesMessageAndInterval() throws Exception {
		when(llm.generate(anyString())).thenThrow(new IllegalStateException("LLM недоступна"));
		assertThat(engine.execute(trigger, event(1L, source)).scheduled()).isTrue();
		for (int i = 0; i < queue.size() && i < 5; i++) {
			engine.resume(trigger, queue.get(i));
		}
		verifyNoInteractions(hints);
		assertThat(memory).isEmpty();
	}


	@Test
	void llmErrorCodeIsAFailureNotAHint() throws Exception {
		when(llm.generate(anyString())).thenReturn("Код ошибки: 2");
		assertThat(engine.execute(trigger, event(1L, source)).scheduled()).isTrue();
		for (int i = 0; i < queue.size() && i < 5; i++) {
			engine.resume(trigger, queue.get(i));
		}
		verify(llm, times(3)).generate(anyString());
		verifyNoInteractions(hints);
		assertThat(memory).isEmpty();
	}

	@Test
	void repeatedEventForSameMessageGivesOneHint() throws Exception {
		trigger = trigger(Map.of("cooldownMinutes", 0));
		run();
		assertThat(engine.execute(trigger, event(2L, source)).completed()).isTrue();
		verify(llm, times(1)).generate(anyString());
	}


	@Test
	void candidatesGetCommonShape() {
		var context = new AutomationExecutionContext(trigger, Event.builder().payload(mapper.createObjectNode()).build());
		JsonNode result = operations.execute("list.candidates", Map.of("items", List.of(Map.of("id", 5, "name", "VPN")), "kind", "task",
				"label", "'Заявка: ' + var.item.name"), context, false, "pure", null);
		assertThat(result.get(0).path("id").asText()).isEqualTo("task:5");
		assertThat(result.get(0).path("refId").asLong()).isEqualTo(5L);
		assertThat(result.get(0).path("label").asText()).isEqualTo("Заявка: VPN");
		assertThat(result.get(0).path("item").path("name").asText()).isEqualTo("VPN");
	}


	private void run() throws Exception {
		assertThat(engine.execute(trigger, event(1L, source)).scheduled()).isTrue();
		assertThat(engine.resume(trigger, queue.getLast()).completed()).isTrue();
	}


	private AutomationTrigger trigger(Map<String, Object> settings) throws Exception {
		try (var input = getClass().getResourceAsStream("/automation/templates/operator-hints.json")) {
			ObjectNode graph = (ObjectNode) mapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
			for (JsonNode parameter : graph.path("nodes").get(1).path("config").path("parameters")) {
				if (settings.containsKey(parameter.path("name").asText())) {
					((ObjectNode) parameter).set("value", mapper.valueToTree(settings.get(parameter.path("name").asText())));
				}
			}
			return AutomationTrigger.builder().id(33L).name("Подсказка оператору").triggerType(TriggerType.MESSAGE_INCOMING)
					.workflowDefinition(graph.toString()).build();
		}
	}


	private Event event(long id, Message message) {
		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("client").put("id", 7L);
		payload.set("message", mapper.valueToTree(message));
		return Event.builder().id(id).triggerType(TriggerType.MESSAGE_INCOMING).payload(payload).build();
	}


	private static Map<String, Object> row(Object... pairs) {
		Map<String, Object> row = new HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			row.put(pairs[i].toString(), pairs[i + 1]);
		}
		row.putIfAbsent("rank", 0.1);
		return row;
	}

}

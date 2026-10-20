package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeMessageGraphTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AutomationWorkflowValueService values = new AutomationWorkflowValueService(mapper);
    private final AutomationActionExecutor actions = mock(AutomationActionExecutor.class);
    private final AutomationScriptRuntime expressions = new AutomationScriptRuntime(mapper, actions, mock(AppSettingsService.class), values);
    private final JevClient jev = mock(JevClient.class);
    private final LlmService llm = mock(LlmService.class);
    private final AutomationOperationTransactionService transactions = mock(AutomationOperationTransactionService.class);
    private final ClientRepository clients = mock(ClientRepository.class);
    private final AutomationWorkflowRunRepository runs = mock(AutomationWorkflowRunRepository.class);
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private AutomationOperationService operations;
    private AutomationWorkflowEngine engine;
    private AutomationTrigger trigger;
    private Client client;
    private Message source;
    private final List<AutomationWorkflowRun> queue = new ArrayList<>();

    @BeforeEach
    void setup() throws Exception {
        var dependencies = Map.<Class<?>, Object>of(ObjectMapper.class, mapper, AutomationScriptRuntime.class, expressions,
                AutomationWorkflowValueService.class, values, AutomationActionExecutor.class, actions,
                JevClient.class, jev, LlmService.class, llm, AutomationOperationTransactionService.class, transactions,
                ClientRepository.class, clients, KnowledgeService.class, knowledge, JdbcTemplate.class, jdbc);
        var ctor = AutomationOperationService.class.getConstructors()[0];
        operations = (AutomationOperationService) ctor.newInstance(Arrays.stream(ctor.getParameterTypes()).map(t -> dependencies.containsKey(t) ? dependencies.get(t) : mock(t)).toArray());
        engine = new AutomationWorkflowEngine(mapper, expressions, runs, mock(AutomationTriggerRepository.class), mock(AutomationPayloadService.class), values,
                mock(AutomationWorkflowNodeService.class), operations, mock(AutomationGraphHistoryService.class), mock(AfterCommitExecutor.class));
        try (var input = getClass().getResourceAsStream("/automation/templates/knowledge.json")) {
            trigger = AutomationTrigger.builder().id(18L).name("Подбор статьи").triggerType(TriggerType.MESSAGE_INCOMING)
                    .workflowDefinition(new String(input.readAllBytes(), StandardCharsets.UTF_8)).build();
        }
        source = Message.builder().id(200L).text("Как настроить VPN?").build();
        client = Client.builder().id(7L).messages(new ArrayList<>(List.of(source))).build();
        when(clients.findById(7L)).thenAnswer(c -> Optional.of(client));
        when(transactions.read(any())).thenAnswer(c -> ((Supplier<?>) c.getArgument(0)).get());
        Map<String, JsonNode> receipts = new HashMap<>();
        when(transactions.once(anyString(), anyString(), any())).thenAnswer(c -> receipts.computeIfAbsent(c.getArgument(0), key -> ((Supplier<JsonNode>) c.getArgument(2)).get()));
        Set<String> claims = new HashSet<>();
        when(jdbc.update(startsWith("insert into automation_graph_memory"), anyString())).thenAnswer(c -> claims.add(c.getArgument(1)) ? 1 : 0);
        when(knowledge.getKnowledgeBase()).thenReturn(List.of(Knowledge.builder().id(9L).title("VPN").texts(List.of("Установите VPN-клиент")).build()));
        when(jev.evaluate(anyString(), any(), any())).thenReturn(Optional.of(mapper.readTree("{\"decision\":{\"choice\":\"9\",\"confidence\":0.99}}")));
        when(llm.askLlm(anyString())).thenReturn("Установите VPN-клиент");
        when(runs.save(any())).thenAnswer(c -> { queue.add(c.getArgument(0)); return c.getArgument(0); });
    }

    @Test
    void loadedMessagesExposeTheirIndividualSourceToFollowingGraphSteps() throws Exception {
        source.setFromSource("PORTAL");
        client.setMessageFrom(MessageFrom.EMAIL);
        var result = operations.execute("data.messages", Map.of("clientId", 7L),
                new AutomationExecutionContext(trigger, event(100L, source)), false, "source-test", null);
        assertThat(result.get(0).path("fromSource").asText()).isEqualTo("PORTAL");
        assertThat(operations.catalog().stream().filter(op -> op.get("id").equals("data.messages"))
                .findFirst().orElseThrow().get("resultSchema").toString()).contains("fromSource");
    }

    @Test
    void selectionAndGenerationUseOnlyFrozenIncomingTextAcrossBackgroundResume() throws Exception {
        var event = event(100L, source);
        ((ObjectNode) event.getPayload()).putObject("task").put("description", "Старая проблема с принтером");
        client.getMessages().add(Message.builder().id(100L).text("Настройте принтер").build());
        assertThat(engine.execute(trigger, event).scheduled()).isTrue();
        assertThat(queue).hasSize(1);
        // A refreshed payload must not replace the original message snapshot.
        ((ObjectNode) queue.get(0).getEventPayload().path("message")).put("text", "Изменённый текст");
        client.getMessages().add(Message.builder().id(201L).isSent(true).isComment(true).text("Служебная заметка").build());
        assertThat(engine.resume(trigger, queue.get(0)).completed()).isTrue();
        verify(jev).evaluate(anyString(), eq(source.getText()), any());
        var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(llm).askLlm(prompt.capture());
        assertThat(prompt.getValue()).contains(source.getText()).doesNotContain("принтер", "Изменённый текст", "Служебная заметка");
        var notes = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(actions, times(2)).execute(eq("client.addInternalNote"), notes.capture(), any());
        assertThat(notes.getAllValues().get(1).path("args").get(0).asText()).contains("#200", "Установите VPN-клиент");
    }

    @Test
    void queuedOldEventDoesNotAnalyzeOrPublishAfterANewerIncomingMessage() throws Exception {
        addNewIncoming();
        assertThat(engine.execute(trigger, event(100L, source)).completed()).isTrue();
        verifyNoInteractions(jev, llm, actions);
        assertThat(queue).isEmpty();
    }

    @Test
    void newerMessageDuringArticleSelectionSuppressesAnnouncementAndGeneration() throws Exception {
        when(jev.evaluate(anyString(), any(), any())).thenAnswer(c -> {
            addNewIncoming();
            return Optional.of(mapper.readTree("{\"decision\":{\"choice\":\"9\",\"confidence\":0.99}}"));
        });
        assertThat(engine.execute(trigger, event(100L, source)).completed()).isTrue();
        verifyNoInteractions(llm, actions);
        assertThat(queue).isEmpty();
    }

    @Test
    void newerMessageWhileGeneratingSuppressesTheObsoleteAnswerButCanUseTheSameArticle() throws Exception {
        assertThat(engine.execute(trigger, event(100L, source)).scheduled()).isTrue();
        when(llm.askLlm(anyString())).thenAnswer(c -> { addNewIncoming(); return "Устаревшая подсказка"; });
        assertThat(engine.resume(trigger, queue.get(0)).completed()).isTrue();
        verify(actions, times(1)).execute(eq("client.addInternalNote"), any(), any());
        when(llm.askLlm(anyString())).thenReturn("Новая подсказка");
        Message latest = client.getMessages().get(client.getMessages().size()-1);
        assertThat(engine.execute(trigger, event(101L, latest)).scheduled()).isTrue();
        assertThat(engine.resume(trigger, queue.get(1)).completed()).isTrue();
        var notes = org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(actions, times(3)).execute(eq("client.addInternalNote"), notes.capture(), any());
        assertThat(notes.getAllValues().toString()).doesNotContain("Устаревшая подсказка").contains("Новая подсказка");
    }

    @Test
    void staleGenerationFailureDoesNotPostAnErrorForAnOldQuestion() throws Exception {
        assertThat(engine.execute(trigger, event(100L, source)).scheduled()).isTrue();
        when(llm.askLlm(anyString())).thenAnswer(c -> { addNewIncoming(); throw new IllegalStateException("LLM недоступен"); });
        int i = 0;
        while (i < queue.size() && i < 4) engine.resume(trigger, queue.get(i++));
        assertThat(i).isEqualTo(3);
        verify(actions, times(1)).execute(eq("client.addInternalNote"), any(), any());
    }

    @Test
    void latestIncomingFiltersBeforeLimitAndUsesArrivalOrderIgnoringNotesDeletedAndOutbound() {
        addNewIncoming(); // ID 201, deliberately old sender date.
        client.getMessages().get(1).setDate(ZonedDateTime.now().minusDays(2));
        client.getMessages().add(Message.builder().id(202L).isSent(true).build());
        client.getMessages().add(Message.builder().id(203L).isComment(true).build());
        client.getMessages().add(Message.builder().id(204L).deleted(true).build());
        client.getMessages().add(Message.builder().id(205L).user(new SystemUser()).build());
        var result = operations.execute("data.messages", Map.of("clientId",7L,"incomingOnly",true,"newestFirstById",true,"limit",1),
                new AutomationExecutionContext(trigger, Event.builder().payload(mapper.createObjectNode()).build()), false, "read", null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).path("id").asLong()).isEqualTo(201L);
    }

    @Test
    void repeatedEventDoesNotScheduleASecondHint() throws Exception {
        assertThat(engine.execute(trigger, event(100L, source)).scheduled()).isTrue();
        assertThat(engine.resume(trigger, queue.get(0)).completed()).isTrue();
        assertThat(engine.execute(trigger, event(101L, source)).completed()).isTrue();
        verify(llm, times(1)).askLlm(anyString());
        verify(actions, times(2)).execute(eq("client.addInternalNote"), any(), any());
    }

    private void addNewIncoming() { client.getMessages().add(Message.builder().id(201L).text("Как распознать фишинг?").build()); }
    private Event event(long id, Message message) throws Exception {
        ObjectNode payload = mapper.createObjectNode(); payload.putObject("client").put("id",7L);
        payload.set("message",mapper.valueToTree(message));
        return Event.builder().id(id).triggerType(TriggerType.MESSAGE_INCOMING).payload(payload).build();
    }
}

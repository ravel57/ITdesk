package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.JevDecisionRepository;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JevRequestJournalTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JevDecisionRepository repository = mock(JevDecisionRepository.class);
    private final Map<Long, JevDecision> stored = new LinkedHashMap<>();
    private JevDecisionRecorder recorder;

    @BeforeEach
    void setup() {
        var transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        recorder = new JevDecisionRecorder(repository, transactionManager);
        var sequence = new AtomicLong();
        when(repository.save(any())).thenAnswer(invocation -> {
            JevDecision row = invocation.getArgument(0);
            if (row.getId() == null) row.setId(sequence.incrementAndGet());
            stored.put(row.getId(), row); return row;
        });
        when(repository.findById(anyLong())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.<Long>getArgument(0))));
    }

    private JevDecisionRecorder.GraphContext graph(String node) {
        return new JevDecisionRecorder.GraphContext(17L, "Умное создание заявки", 123L, "execution-id", node, "Выбрать сервис", "ai.decide", "client=7, message=8");
    }
    private Map<String, Object> choice() {
        return Map.of("type", "choice", "instructions", "Какой сервис?", "criteria", Map.of("7", "VPN", "__none__", "Ни один"));
    }

    @Test
    void graphQuestionsHaveRequestEventAndNodeAndNeverInventAThreshold() throws Exception {
        JevDecisionRecorder.inGraph(graph("node-1"), () -> {
            var exchange = recorder.begin("automation=17", Map.of("decision", choice(), "needed", Map.of("type", "noul"), "impact", Map.of("type", "score")));
            assertThat(stored.values()).allSatisfy(row -> {
                assertThat(row.getOutcome()).isEqualTo("PENDING"); assertThat(row.getConfidence()).isNull();
            });
            try { recorder.complete(exchange, mapper.readTree("{\"decision\":{\"choice\":\"7\",\"confidence\":0.9},\"needed\":{\"noul\":0.2},\"impact\":{\"score\":3}}")); }
            catch (Exception e) { throw new RuntimeException(e); }
            return null;
        });
        assertThat(stored).hasSize(3);
        assertThat(stored.values()).allSatisfy(row -> {
            assertThat(row.getSource()).isEqualTo("GRAPH"); assertThat(row.getEventId()).isEqualTo(123);
            assertThat(row.getNodeId()).isEqualTo("node-1"); assertThat(row.getContext()).isEqualTo("client=7, message=8");
            assertThat(row.getThreshold()).isNull(); assertThat(row.getResponseStatus()).isEqualTo("OK");
            assertThat(row.getOutcome()).isEqualTo("ANSWERED");
        });
        assertThat(stored.values().stream().map(JevDecision::getRequestId).distinct().count()).isEqualTo(1);
        assertThat(stored.values()).anySatisfy(row -> { assertThat(row.getChoice()).isEqualTo("VPN"); assertThat(row.getConfidence()).isEqualTo(0.9); });
        assertThat(stored.values()).anySatisfy(row -> { assertThat(row.getAnswerType()).isEqualTo("score"); assertThat(row.getConfidence()).isEqualTo(3.0); });
    }

    @Test
    void legacyPolicyEnrichesTheRawQuestionWithoutADuplicate() throws Exception {
        JevDecisionRecorder.inAction(new JevDecisionRecorder.ActionContext(14L, "Приоритет", "setPriority", 123L), () -> {
            var exchange = recorder.begin("task=4", Map.of("priority", choice()));
            try { recorder.complete(exchange, mapper.readTree("{\"priority\":{\"choice\":\"7\",\"confidence\":0.6}}")); }
            catch (Exception e) { throw new RuntimeException(e); }
            recorder.record("task=4", "priority", "VPN", 0.6, 0.7, JevDecisionRecorder.Outcome.REJECTED);
        });
        assertThat(stored).hasSize(1);
        var row = stored.values().iterator().next();
        assertThat(row.getThreshold()).isEqualTo(0.7); assertThat(row.getOutcome()).isEqualTo("REJECTED");
        assertThat(row.getEventId()).isEqualTo(123); assertThat(row.getSource()).isEqualTo("LEGACY");
    }

    @Test
    void disabledFailedAndMalformedAnswersAreVisibleWithoutFakeZeroConfidence() throws Exception {
        var disabled = recorder.begin("disabled", Map.of("q", choice())); recorder.unavailable(disabled, "DISABLED", "Интеграция выключена");
        var failed = recorder.begin("failed", Map.of("q", choice())); recorder.unavailable(failed, "ERROR", "Jev вернул HTTP 500");
        var malformed = recorder.begin("malformed", Map.of("q", choice(), "missing", choice()));
        recorder.complete(malformed, mapper.readTree("{\"q\":{\"choice\":\"unknown\",\"confidence\":0.9}}"));
        recorder.record("malformed", "q", "fallback", 0.0, 0.7, JevDecisionRecorder.Outcome.REJECTED);
        assertThat(stored).hasSize(4);
        assertThat(stored.values()).allSatisfy(row -> assertThat(row.getConfidence()).isNull());
        assertThat(stored.values().stream().map(JevDecision::getOutcome)).containsExactly("SKIPPED", "FAILED", "INVALID", "INVALID");
    }

    @Test
    void noOptionsAndEarlyReturnAreRecordedOnceAndNestedScopesRestore() {
        JevDecisionRecorder.inGraph(graph("outer"), () -> {
            recorder.noOptions("automation=17", "Что выбрать?");
            recorder.recordUnsentGraphAnalysis("automation=17", "signal.verify");
            JevDecisionRecorder.inGraph(graph("inner"), () -> { recorder.recordUnsentGraphAnalysis("automation=17", "signal.verify"); return null; });
            recorder.recordUnsentGraphAnalysis("automation=17", "signal.verify");
            return null;
        });
        recorder.recordUnsentGraphAnalysis("automation=17", "signal.verify");
        assertThat(stored).hasSize(2);
        assertThat(stored.values().stream().map(JevDecision::getNodeId)).containsExactly("outer", "inner");
        assertThat(stored.values().stream().map(JevDecision::getResponseStatus)).containsExactly("NO_OPTIONS", "NO_REQUEST");
    }

    @Test
    void httpResponseCanSerializeMissingConfidenceAndGraphMetadata() throws Exception {
        JevDecisionRecorder.inGraph(graph("node-1"), () -> {
            var exchange = recorder.begin("automation=17", Map.of("q", choice()));
            recorder.unavailable(exchange, "ERROR", "Jev вернул HTTP 500"); return null;
        });
        var converter = new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter();
        var output = new org.springframework.mock.http.MockHttpOutputMessage();
        converter.write(Map.of("decisions", List.copyOf(stored.values())), org.springframework.http.MediaType.APPLICATION_JSON, output);
        String json = output.getBodyAsString();
        assertThat(json).contains("\"confidence\":null", "\"outcome\":\"FAILED\"", "\"eventId\":123", "\"nodeId\":\"node-1\"");
    }

    @Test
    void journalFailureDoesNotBreakTheAutomation() {
        doThrow(new IllegalStateException("database unavailable")).when(repository).save(any());
        assertThatCode(() -> {
            var exchange = recorder.begin("task=4", Map.of("q", choice()));
            recorder.complete(exchange, mapper.createObjectNode());
            recorder.record("task=4", "q", "value", 0.8, null, JevDecisionRecorder.Outcome.ANSWERED);
        }).doesNotThrowAnyException();
    }
}

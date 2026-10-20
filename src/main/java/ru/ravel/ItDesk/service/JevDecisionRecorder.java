package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.JevDecisionRepository;

import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.Supplier;

/** One journal row per question in an actual Jev request, independent of graph/business thresholds. */
@Service
public class JevDecisionRecorder {
    private static final Logger log = LoggerFactory.getLogger("JEV");
    private static final int RETENTION_DAYS = 30;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ThreadLocal<ActionContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<GraphContext> GRAPH = new ThreadLocal<>();
    private static final ThreadLocal<Exchange> LAST = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> GRAPH_REQUEST = new ThreadLocal<>();
    private final JevDecisionRepository repository;
    private final TransactionTemplate newTransaction;

    public JevDecisionRecorder(JevDecisionRepository repository, PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public enum Outcome { ACCEPTED, REJECTED, NONE, UNDECIDED, ANSWERED, FAILED, SKIPPED, PENDING, INVALID }

    public record ActionContext(Long ruleId, String ruleName, String action, Long eventId) {
        public ActionContext(Long ruleId, String ruleName, String action) { this(ruleId, ruleName, action, null); }
    }
    public record GraphContext(Long ruleId, String ruleName, Long eventId, String executionId,
                               String nodeId, String nodeLabel, String operation, String context) {}
    private record Entry(Long id, String key, JsonNode definition) {}
    public record Exchange(String context, List<Entry> entries) {}

    public static void inAction(ActionContext context, Runnable action) {
        ActionContext previous = CURRENT.get(); Exchange last = LAST.get();
        CURRENT.set(context); LAST.remove();
        try { action.run(); } finally { restore(CURRENT, previous); restore(LAST, last); }
    }

    public static <T> T inGraph(GraphContext context, Supplier<T> action) {
        GraphContext previous = GRAPH.get(); Exchange last = LAST.get(); Boolean captured = GRAPH_REQUEST.get();
        GRAPH.set(context); LAST.remove(); GRAPH_REQUEST.set(false);
        try { return action.get(); } finally { restore(GRAPH, previous); restore(LAST, last); restore(GRAPH_REQUEST, captured); }
    }

    private static <T> void restore(ThreadLocal<T> local, T value) { if (value == null) local.remove(); else local.set(value); }

    /** Persist before HTTP, so an interrupted process leaves a visible pending request. No request state is stored. */
    public Exchange begin(String context, Map<String, Object> questions) {
        LAST.remove();
        if (GRAPH.get() != null) GRAPH_REQUEST.set(true);
        try {
            Exchange exchange = newTransaction.execute(status -> {
                String requestId = UUID.randomUUID().toString();
                List<Entry> entries = new ArrayList<>();
                Map<String, Object> actual = questions == null || questions.isEmpty() ? Map.of("request", Map.of("instructions", "Запрос Jev")) : questions;
                for (var question : actual.entrySet()) {
                    JsonNode definition = MAPPER.valueToTree(question.getValue());
                    JevDecision row = base(context, question.getKey());
                    row.setRequestId(requestId);
                    row.setQuestionTitle(limit(definition.path("instructions").asText(question.getKey()), 1024));
                    row.setAnswerType(definition.path("type").asText(null));
                    row.setOutcome(Outcome.PENDING.name()); row.setResponseStatus("STARTED");
                    entries.add(new Entry(repository.save(row).getId(), question.getKey(), definition));
                }
                return new Exchange(context, List.copyOf(entries));
            });
            LAST.set(exchange);
            return exchange;
        } catch (Exception e) { warn(e); return null; }
    }

    public void complete(Exchange exchange, JsonNode answers) {
        update(exchange, (row, entry) -> {
            JsonNode answer = answers == null ? MAPPER.nullNode() : answers.path(entry.key());
            String kind = entry.definition().path("type").asText();
            JsonNode score = answer.path(kind.equals("noul") ? "noul" : kind.equals("score") ? "score" : "confidence");
            String choice = answer.path("choice").asText(null);
            boolean valid = score.isNumber() && Double.isFinite(score.asDouble())
                    && (kind.equals("score") || score.asDouble() >= 0 && score.asDouble() <= 1);
            if (kind.equals("choice")) valid &= choice != null && entry.definition().path("criteria").has(choice);
            if (!Set.of("choice", "noul", "score").contains(kind)) valid = false;
            if (!valid) {
                row.setOutcome(Outcome.INVALID.name()); row.setResponseStatus("INVALID_RESPONSE");
                row.setConfidence(null); row.setErrorMessage("Ответ Jev не соответствует типу вопроса"); return;
            }
            row.setResponseStatus("OK"); row.setConfidence(score.asDouble()); row.setErrorMessage(null);
            row.setOutcome("__none__".equals(choice) ? Outcome.NONE.name() : Outcome.ANSWERED.name());
            row.setChoice(limit(kind.equals("choice") ? entry.definition().path("criteria").path(choice).asText(choice)
                    : kind.equals("noul") ? "Вероятность ответа «Да»" : "Оценка Jev", 255));
        });
    }

    public void unavailable(Exchange exchange, String status, String reason) {
        update(exchange, (row, entry) -> {
            row.setConfidence(null); row.setResponseStatus(status); row.setErrorMessage(limit(reason, 1000));
            row.setOutcome(Set.of("DISABLED", "NO_QUESTIONS", "NO_OPTIONS", "NO_REQUEST").contains(status) ? Outcome.SKIPPED.name() : Outcome.FAILED.name());
        });
    }

    public void noOptions(String context, String question) {
        Exchange exchange = begin(context, Map.of("decision", Map.of("instructions", question, "type", "choice")));
        unavailable(exchange, "NO_OPTIONS", "Нет вариантов для выбора; запрос Jev не отправлялся");
    }

    public void recordUnsentGraphAnalysis(String context, String operation) {
        if (GRAPH.get() == null || Boolean.TRUE.equals(GRAPH_REQUEST.get())) return;
        Exchange exchange = begin(context, Map.of("analysis", Map.of("instructions", operation)));
        unavailable(exchange, "NO_REQUEST", "Шаг завершён без запроса Jev: проверьте входные данные и доступность интеграции");
    }

    private interface RowUpdate { void apply(JevDecision row, Entry entry); }
    private void update(Exchange exchange, RowUpdate update) {
        if (exchange == null) return;
        try { newTransaction.executeWithoutResult(status -> {
            for (Entry entry : exchange.entries()) repository.findById(entry.id()).ifPresent(row -> {
                update.apply(row, entry); repository.save(row);
            });
        }); } catch (Exception e) { warn(e); }
    }

    /** Legacy policy methods enrich the corresponding raw question instead of appending a duplicate. */
    public void record(String context, String question, String choice, double confidence, Double threshold, Outcome outcome) {
        Exchange last = LAST.get();
        Entry existing = last != null && Objects.equals(context, last.context())
                ? last.entries().stream().filter(entry -> entry.key().equals(question)).findFirst().orElse(null) : null;
        try { newTransaction.executeWithoutResult(status -> {
            JevDecision row = existing == null ? base(context, question) : repository.findById(existing.id()).orElseGet(() -> base(context, question));
            if (existing != null && row.getId() != null && !"OK".equals(row.getResponseStatus()) && !"RECORDED".equals(row.getResponseStatus())) return;
            row.setChoice(limit(choice, 255)); row.setConfidence(confidence); row.setThreshold(threshold); row.setOutcome(outcome.name());
            row.setResponseStatus("OK"); repository.save(row);
        }); } catch (Exception e) { warn(e); }
    }

    private JevDecision base(String context, String question) {
        GraphContext graph = GRAPH.get(); ActionContext action = CURRENT.get();
        return JevDecision.builder().createdAt(ZonedDateTime.now())
                .ruleId(graph != null ? graph.ruleId() : action == null ? null : action.ruleId())
                .ruleName(limit(graph != null ? graph.ruleName() : action == null ? null : action.ruleName(), 255))
                .action(limit(graph != null ? graph.operation() : action == null ? null : action.action(), 64))
                .source(graph == null ? "LEGACY" : "GRAPH")
                .eventId(graph != null ? graph.eventId() : action == null ? null : action.eventId())
                .executionId(graph == null ? null : limit(graph.executionId(), 64))
                .nodeId(graph == null ? null : limit(graph.nodeId(), 255))
                .nodeLabel(graph == null ? null : limit(graph.nodeLabel(), 255))
                .context(limit(graph != null && graph.context() != null && !graph.context().isBlank() ? graph.context() : context, 255))
                .question(limit(question, 64)).build();
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void cleanup() {
        int removed = repository.deleteOlderThan(ZonedDateTime.now().minusDays(RETENTION_DAYS));
        if (removed > 0) log.debug("Jev decision log: removed {} records older than {} days", removed, RETENTION_DAYS);
    }
    private void warn(Exception error) { log.warn("Unable to write Jev decision log: {}", error.getMessage()); }
    private String limit(String value, int max) { return value == null ? null : value.length() <= max ? value : value.substring(0, max); }
}

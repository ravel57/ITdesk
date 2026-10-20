package ru.ravel.ItDesk.migration;

import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.JevDecisionRepository;
import java.sql.DriverManager;
import java.time.ZonedDateTime;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JevJournalQueryTest {
    @Test
    void statsAndFiltersSeparateGraphStepsAndIgnoreMissingConfidence() throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL for PostgreSQL tests");
        String schema = "jev_query_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url); var sql = connection.createStatement()) {
            sql.execute("create schema " + schema);
            try {
                var config = new Configuration().addAnnotatedClass(JevDecision.class)
                        .setProperty("hibernate.connection.url", url)
                        .setProperty("hibernate.default_schema", schema)
                        .setProperty("hibernate.hbm2ddl.auto", "create-drop")
                        .setProperty("hibernate.show_sql", "false");
                try (var factory = config.buildSessionFactory(); var session = factory.openSession()) {
                    var transaction = session.beginTransaction();
                    session.persist(row("a", "ANSWERED", 0.8));
                    session.persist(row("a", "FAILED", null));
                    session.persist(row("b", "NONE", 0.9));
                    transaction.commit();
                    String aggregate = JevDecisionRepository.class.getMethod("stats", ZonedDateTime.class).getAnnotation(Query.class).value();
                    var result = session.createQuery(aggregate, Object[].class).setParameter("since", ZonedDateTime.now().minusDays(1)).getResultList();
                    assertThat(result).hasSize(2);
                    Object[] group = result.stream().filter(values -> values[2].equals("a")).findFirst().orElseThrow();
                    assertThat(((Number) group[8]).longValue()).isEqualTo(2);
                    assertThat(((Number) group[9]).doubleValue()).isEqualTo(0.8);
                    assertThat(((Number) group[10]).longValue()).isEqualTo(1);
                    assertThat(((Number) group[11]).longValue()).isEqualTo(1);
                    String search = Arrays.stream(JevDecisionRepository.class.getMethods()).filter(method -> method.getName().equals("search"))
                            .findFirst().orElseThrow().getAnnotation(Query.class).value();
                    var filtered = session.createQuery(search, JevDecision.class)
                            .setParameter("since", ZonedDateTime.now().minusDays(1)).setParameter("action", "ai.decide")
                            .setParameter("question", "decision").setParameter("outcome", null)
                            .setParameter("ruleId", 17L).setParameter("nodeId", "a").getResultList();
                    assertThat(filtered).hasSize(2).allSatisfy(row -> assertThat(row.getNodeId()).isEqualTo("a"));
                }
            } finally { sql.execute("drop schema if exists " + schema + " cascade"); }
        }
    }
    private JevDecision row(String node, String outcome, Double confidence) {
        return JevDecision.builder().createdAt(ZonedDateTime.now()).ruleId(17L).ruleName("Сценарий").nodeId(node)
                .nodeLabel(node).action("ai.decide").question("decision").questionTitle("Какой сервис?").answerType("choice")
                .confidence(confidence).outcome(outcome).build();
    }
}

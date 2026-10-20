package ru.ravel.ItDesk.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JevGraphJournalMigrationTest {
    @Test
    void restoresGraphAnswersAndErrorsWithoutDuplicatingLegacyDecisionsOrRepeatedImports() throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL for PostgreSQL tests");
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "jev_journal_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("create schema " + schema); sql.execute("set local search_path to " + schema);
                sql.execute("""
                    create table jev_decision(id bigserial primary key,created_at timestamptz not null,rule_id bigint,rule_name varchar(255),
                        action varchar(64),context varchar(255),question varchar(64),choice varchar(255),confidence double precision not null,
                        threshold double precision,outcome varchar(32));
                    create table automation_graph_trace(id bigint primary key,created_at timestamptz,trigger_id bigint,execution_id varchar,step jsonb);
                    create table automation_trigger(id bigint primary key,name text,workflow_definition text);
                    insert into automation_trigger values(17,'Сценарий','{"nodes":[{"id":"failed","config":{"operation":"ai.decide"}}]}');
                    insert into jev_decision(created_at,rule_id,question,confidence,threshold,outcome) values(now(),17,'legacy',0.8,0.7,'ACCEPTED');
                    insert into automation_graph_trace values
                       (1,now(),17,'run','{"nodeId":"select","label":"Выбрать сервис","detail":"ai.decide","status":"EXECUTED","input":{"question":"Какой сервис?","kind":"choice"},"output":{"status":"OK","choice":"7","probability":0.9,"value":{"name":"VPN"}}}'),
                       (2,now(),17,'run','{"nodeId":"failed","label":"Проверка","detail":"HTTP error","status":"FAILED","input":{"kind":"noul"},"output":null}'),
                       (3,now(),17,'run','{"nodeId":"analyze","detail":"signal.analyze","status":"EXECUTED","output":{"probability":0.8}}'),
                       (4,now(),17,'run','{"nodeId":"skip","detail":"ai.decide","status":"EXECUTED","input":{"kind":"choice"},"output":{"status":"NO_OPTIONS","available":false}}');
                    """);
                String migration;
                try (var input = getClass().getResourceAsStream("/db/migration/V66__jev_graph_decision_journal.sql")) {
                    migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
                sql.execute(migration); sql.execute(migration);
                try (var rows = sql.executeQuery("select count(*) from jev_decision")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(4); }
                try (var rows = sql.executeQuery("select choice,confidence,threshold,outcome,source,node_label,question_title from jev_decision where trace_id=1")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("VPN");
                    assertThat(rows.getDouble(2)).isEqualTo(0.9); assertThat(rows.getObject(3)).isNull();
                    assertThat(rows.getString(4)).isEqualTo("ANSWERED"); assertThat(rows.getString(5)).isEqualTo("GRAPH_TRACE");
                    assertThat(rows.getString(6)).isEqualTo("Выбрать сервис"); assertThat(rows.getString(7)).isEqualTo("Какой сервис?");
                }
                try (var rows = sql.executeQuery("select confidence,outcome,response_status from jev_decision where trace_id=2")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getObject(1)).isNull();
                    assertThat(rows.getString(2)).isEqualTo("FAILED"); assertThat(rows.getString(3)).isEqualTo("ERROR");
                }
                try (var rows = sql.executeQuery("select outcome from jev_decision where trace_id=4")) { rows.next(); assertThat(rows.getString(1)).isEqualTo("SKIPPED"); }
                try (var rows = sql.executeQuery("select threshold,source,response_status from jev_decision where question='legacy'")) {
                    rows.next(); assertThat(rows.getDouble(1)).isEqualTo(0.7); assertThat(rows.getString(2)).isEqualTo("LEGACY");
                    assertThat(rows.getString(3)).isEqualTo("RECORDED");
                }
            } finally { connection.rollback(); }
        }
    }
}

package ru.ravel.ItDesk.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class KnowledgeMessageMigrationTest {
    @Test
    void patchesTheStoredGraphOnceAndPreservesUserSettingsAndBackup() throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL for PostgreSQL tests");
        var mapper = new ObjectMapper();
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "knowledge_message_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("create schema " + schema); sql.execute("set local search_path to " + schema);
                sql.execute("""
                    create table automation_trigger(id bigint primary key,name text,description text,workflow_definition text,workflow_version integer);
                    create table app_settings(id bigint primary key,jev_reply_required_threshold double precision,jev_reply_not_required_threshold double precision,jev_priority_excluded text,jev_thresholds jsonb);
                    insert into app_settings values(1,0.83,0.17,'Отложен','{"suggestKnowledgeArticle":0.91}');
                    insert into automation_trigger values(18,'Подбор статьи базы знаний (Jev)','custom description','{"custom":true}',2),
                        (20,'Не изменять','custom','{"nodes":[],"entryNodeId":"other"}',3);
                    """);
                sql.execute(resource("V64__transparent_automation_graphs.sql"));
                sql.execute("""
                    update automation_trigger set name='Мои подсказки',workflow_definition=jsonb_set(
                        jsonb_set(workflow_definition::jsonb,'{sectionOrder}','["4. Подготовить ответ в фоне","1. Настройки"]'),
                        '{nodes,1,config,description}','"Мои параметры"')::text where id=18;
                    """);
                String before;
                try (var rows = sql.executeQuery("select workflow_definition from automation_trigger where id=18")) { rows.next(); before = rows.getString(1); }
                String migration = resource("V67__knowledge_hints_latest_message.sql");
                sql.execute(migration); sql.execute(migration);
                try (var rows = sql.executeQuery("select name,workflow_definition from automation_trigger where id=18")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("Мои подсказки");
                    JsonNode graph = mapper.readTree(rows.getString(2));
                    var settings = java.util.stream.StreamSupport.stream(graph.path("nodes").spliterator(), false)
                            .filter(n -> n.path("id").asText().equals("knowledge-2")).findFirst().orElseThrow().path("config");
                    assertThat(settings.path("parameters").get(0).path("value").asDouble()).isEqualTo(0.91);
                    assertThat(settings.path("description").asText()).isEqualTo("Мои параметры");
                    assertThat(graph.path("sectionOrder").get(0).asText()).isEqualTo("4. Подготовить ответ в фоне");
                    JsonNode template;
                    try (var input = getClass().getResourceAsStream("/automation/templates/knowledge.json")) { template = mapper.readTree(input); }
                    assertThat(graph.path("nodes").size()).isEqualTo(template.path("nodes").size());
                    assertThat(graph.path("edges")).isEqualTo(template.path("edges"));
                    for (JsonNode node : graph.path("nodes")) {
                        if (node.path("id").asText().equals("knowledge-2")) continue;
                        assertThat(java.util.stream.StreamSupport.stream(template.path("nodes").spliterator(), false)
                                .filter(n -> n.path("id").equals(node.path("id"))).findFirst()).contains(node);
                    }
                }
                try (var rows = sql.executeQuery("select definition->>'workflow_definition' from automation_workflow_backup where trigger_id=18 and migration_version=67")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo(before); assertThat(rows.next()).isFalse();
                }
                try (var rows = sql.executeQuery("select workflow_definition from automation_trigger where id=20")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("{\"nodes\":[],\"entryNodeId\":\"other\"}");
                }
            } finally { connection.rollback(); }
        }
    }
    private String resource(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/db/migration/" + name)) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }
}

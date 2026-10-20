package ru.ravel.ItDesk.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CurrentAutomationConfigurationMigrationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> names = List.of("Приветственное сообщение", "Просроченная задача", "Сообщение вне рабочего времени",
            "SLA скоро нарушится", "SLA нарушен", "Авто-новая заявка (Простая логика)", "Приоритет (Jev)", "Тип заявки (Jev)",
            "Эскалация на следующую линию (Jev)", "Дубли заявок и инциденты (Jev)", "Умное создание заявки (Jev)",
            "Нужен ответ (Jev)", "Подбор статьи базы знаний (Jev)", "Детектор массовых инцидентов (Jev)",
            "Проверка восстановления после инцидента (Jev)", "Инцидент по проблеме Zabbix", "Восстановление по Zabbix",
            "Обновления проблем Zabbix в инциденте");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoresCurrentConfigurationOnEmptyAndCustomizedDatabasesWithoutCopyingRuntimeHistory(boolean customized) throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL for PostgreSQL tests");
        String migration;
        try (var input = getClass().getResourceAsStream("/db/migration/V68__current_automation_configuration.sql")) {
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        int start = migration.indexOf("$snapshot$\n") + "$snapshot$\n".length();
        JsonNode snapshot = mapper.readTree(migration.substring(start, migration.indexOf("\n$snapshot$", start)));
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "automation_config_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("create schema " + schema); sql.execute("set local search_path to " + schema);
                sql.execute("""
                    create table automation_folder(id bigserial primary key,name varchar(255) not null,collapsed boolean not null default false,
                        display_order integer not null default 0,parent_id bigint references automation_folder(id));
                    create table automation_trigger(id bigserial primary key,name varchar(255),description varchar(255),trigger_type varchar(255),
                        expression varchar(255),action varchar(255),else_action text,workflow_definition text,workflow_version integer not null default 1,
                        automation_rule_status varchar(255),stop_processing boolean not null default false,folder_id bigint references automation_folder(id),
                        display_order integer not null default 0,order_number integer not null default 0,match_count bigint not null default 0,
                        failure_count bigint not null default 0,last_matched_at timestamptz,last_failed_at timestamptz,last_error varchar(2000));
                    create table automation_workflow_backup(trigger_id bigint not null,migration_version integer not null,definition jsonb not null,
                        saved_at timestamptz not null default now(),primary key(trigger_id,migration_version));
                    """);
                if (customized) sql.execute("""
                    alter sequence automation_folder_id_seq restart with 100;
                    alter sequence automation_trigger_id_seq restart with 1000;
                    insert into automation_folder(name,collapsed,display_order) values('Jev',true,20);
                    insert into automation_folder(name,parent_id,collapsed,display_order) values('Работа с заявками',100,false,10);
                    insert into automation_folder(name,collapsed,display_order) values('Свои сценарии',true,0);
                    insert into automation_trigger(name,workflow_definition,folder_id,match_count,failure_count,last_error)
                        values('Подбор статьи базы знаний (Jev)','{"old":true}',null,42,7,'Старая ошибка'),
                        ('Приветственное сообщение','{"old":true}',null,13,0,null),
                        ('Дополнительная проверка Jev','{"custom":true}',101,9,0,null),
                        ('Мой сценарий','{"custom":true}',102,15,0,null);
                    """);
                sql.execute(migration);
                Map<String, Long> folderIds = new HashMap<>();
                try (var rows = sql.executeQuery("select f.id,coalesce(p.name || '/','') || f.name as path,f.collapsed from automation_folder f left join automation_folder p on p.id=f.parent_id")) {
                    while (rows.next()) {
                        folderIds.put(rows.getString(2), rows.getLong(1));
                        assertThat(rows.getBoolean(3)).isEqualTo(Set.of("Jev/Работа с заявками", "Свои сценарии").contains(rows.getString(2)));
                    }
                }
                assertThat(folderIds.keySet()).contains("Простая логика", "Jev", "Jev/Работа с заявками", "Jev/Работа с сообщениями", "Zabbix");
                assertThat(folderIds).hasSize(customized ? 6 : 5);
                if (customized) assertThat(folderIds.get("Jev")).isEqualTo(100L);
                try (var rows = sql.executeQuery("select name from automation_folder where parent_id is null order by display_order,id")) {
                    List<String> roots = new ArrayList<>(); while (rows.next()) roots.add(rows.getString(1));
                    assertThat(roots).startsWith("Простая логика", "Jev", "Zabbix");
                }
                List<String> actualNames = new ArrayList<>();
                Map<String, JsonNode> migrated = new HashMap<>();
                try (var rows = sql.executeQuery("select to_jsonb(t)::text from automation_trigger t order by order_number,id")) {
                    while (rows.next()) { JsonNode t = mapper.readTree(rows.getString(1)); actualNames.add(t.path("name").asText()); migrated.put(t.path("name").asText(),t); }
                }
                assertThat(actualNames.stream().filter(names::contains).toList()).isEqualTo(names);
                assertThat(migrated).hasSize(customized ? 20 : 18);
                for (JsonNode expected : snapshot.path("triggers")) {
                    JsonNode actual = migrated.get(expected.path("name").asText());
                    for (String field : List.of("name","description","trigger_type","expression","action","else_action",
                            "workflow_version","automation_rule_status","stop_processing","display_order")) {
                        assertThat(actual.get(field)).as(expected.path("name").asText() + ": " + field).isEqualTo(expected.get(field));
                    }
                    assertThat(mapper.readTree(actual.path("workflow_definition").asText())).isEqualTo(expected.path("workflow_definition"));
                    long sourceFolderId = expected.path("folder_id").asLong();
                    JsonNode folder = java.util.stream.StreamSupport.stream(snapshot.path("folders").spliterator(),false)
                            .filter(f -> f.path("id").asLong() == sourceFolderId).findFirst().orElseThrow();
                    String path = folder.path("name").asText();
                    if (!folder.path("parent_id").isNull()) path = "Jev/" + path;
                    assertThat(actual.path("folder_id").asLong()).isEqualTo(folderIds.get(path));
                }
                assertThat(migrated.get("Авто-новая заявка (Простая логика)").path("automation_rule_status").asText()).isEqualTo("DISABLED");
                assertThat(migrated.values().stream().filter(t -> t.path("automation_rule_status").asText().equals("ENABLED"))).hasSize(17);
                if (customized) {
                    assertThat(migrated.get("Подбор статьи базы знаний (Jev)").path("id").asLong()).isEqualTo(1000L);
                    assertThat(migrated.get("Подбор статьи базы знаний (Jev)").path("match_count").asLong()).isEqualTo(42L);
                    assertThat(migrated.get("Подбор статьи базы знаний (Jev)").path("failure_count").asLong()).isEqualTo(7L);
                    assertThat(migrated.get("Подбор статьи базы знаний (Jev)").path("last_error").asText()).isEqualTo("Старая ошибка");
                    assertThat(migrated.get("Мой сценарий").path("workflow_definition").asText()).isEqualTo("{\"custom\":true}");
                    assertThat(migrated.get("Мой сценарий").path("match_count").asLong()).isEqualTo(15L);
                    assertThat(migrated.get("Дополнительная проверка Jev").path("folder_id").asLong()).isEqualTo(101L);
                    assertThat(actualNames.indexOf("Дополнительная проверка Jev")).isBetween(actualNames.indexOf("Дубли заявок и инциденты (Jev)"), actualNames.indexOf("Умное создание заявки (Jev)"));
                    assertThat(actualNames.get(actualNames.size()-1)).isEqualTo("Мой сценарий");
                    try (var rows = sql.executeQuery("select definition->>'workflow_definition' from automation_workflow_backup where migration_version=68 and trigger_id=1000")) {
                        assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("{\"old\":true}");
                    }
                } else assertThat(migrated.values()).allSatisfy(t -> {
                    assertThat(t.path("match_count").asLong()).isZero(); assertThat(t.path("failure_count").asLong()).isZero();
                });
                String state;
                try (var rows = sql.executeQuery("select jsonb_build_object('folders',(select jsonb_agg(to_jsonb(f) order by id) from automation_folder f),'triggers',(select jsonb_agg(to_jsonb(t) order by id) from automation_trigger t))::text")) { rows.next(); state=rows.getString(1); }
                sql.execute(migration);
                try (var rows = sql.executeQuery("select jsonb_build_object('folders',(select jsonb_agg(to_jsonb(f) order by id) from automation_folder f),'triggers',(select jsonb_agg(to_jsonb(t) order by id) from automation_trigger t))::text")) { rows.next(); assertThat(rows.getString(1)).isEqualTo(state); }
                try (var rows = sql.executeQuery("select jsonb_array_length(folders) from automation_layout_backup where migration_version=68")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(customized ? 3 : 0);
                }
                try (var rows = sql.executeQuery("select count(*) from automation_workflow_backup where migration_version=68")) { rows.next(); assertThat(rows.getInt(1)).isEqualTo(customized ? 4 : 0); }
            } finally { connection.rollback(); }
        }
    }
}

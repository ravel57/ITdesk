package ru.ravel.ItDesk.migration;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransparentAutomationMigrationTest {
    @Test
    void migrationPreservesOriginalsCopiesSettingsAndCanBeParsedByPostgresql() throws Exception {
        String url=System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url!=null&&!url.isBlank(),"Set ITDESK_TEST_POSTGRES_URL for PostgreSQL integration tests");
        try(var connection=DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try(var sql=connection.createStatement()) {
                String schema="automation_graph_"+UUID.randomUUID().toString().replace("-","");
                sql.execute("create schema "+schema);sql.execute("set local search_path to "+schema);
                sql.execute("""
                create table automation_trigger(id bigint primary key,name text,description text,workflow_definition text,workflow_version integer);
                create table app_settings(id bigint primary key,jev_reply_required_threshold double precision,jev_reply_not_required_threshold double precision,jev_priority_excluded text,jev_thresholds jsonb);
                insert into app_settings values(1,0.83,0.17,'Отложен','{"setPriority":0.91}');
                insert into automation_trigger values(1,'Приоритет (Jev)','custom description','{"custom":true}',2),(2,'Нужен ответ (Jev)','reply','{"reply":true}',2);
                """);
                String migration;
                try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V64__transparent_automation_graphs.sql"))){migration=new String(in.readAllBytes(),StandardCharsets.UTF_8);}
                sql.execute(migration);
                try(var rows=sql.executeQuery("select workflow_version,workflow_definition::jsonb#>>'{nodes,1,config,parameters,0,value}' from automation_trigger where id=1")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getInt(1)).isEqualTo(3);assertThat(rows.getDouble(2)).isEqualTo(0.91);
                }
                try(var rows=sql.executeQuery("select definition->>'workflow_definition',definition->>'description' from automation_workflow_backup where trigger_id=1")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("{\"custom\":true}");assertThat(rows.getString(2)).isEqualTo("custom description");
                }
                try(var rows=sql.executeQuery("select workflow_definition::jsonb#>>'{nodes,1,config,parameters,0,value}' from automation_trigger where id=2")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getDouble(1)).isEqualTo(0.83);
                }
                // The receipt claim is atomic and can be replayed without a second side effect.
                sql.execute("insert into automation_operation_receipt(execution_key,operation,result) values('event:node:0','task.create','{\"id\":7}')");
                assertThat(sql.executeUpdate("insert into automation_operation_receipt(execution_key,operation) values('event:node:0','task.create') on conflict do nothing")).isZero();
                assertThat(sql.executeUpdate("insert into automation_graph_memory(memory_key) values('article:7:10') on conflict do nothing")).isEqualTo(1);
                assertThat(sql.executeUpdate("insert into automation_graph_memory(memory_key) values('article:7:10') on conflict do nothing")).isZero();
            } finally { connection.rollback(); }
        }
    }
}

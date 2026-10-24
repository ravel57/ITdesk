package ru.ravel.ItDesk.migration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.session.jdbc.autoconfigure.JdbcSessionProperties;
import org.springframework.boot.sql.init.DatabaseInitializationMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SessionSchemaInitializationTest {
    @Test
    void applicationUsesRepeatableSessionBootstrapAndReportsRealErrors() throws Exception {
        var source = new YamlPropertySourceLoader().load("test", new ClassPathResource("application.yaml")).getFirst();
        var properties = new Binder(ConfigurationPropertySources.from(source))
                .bind("spring.session.jdbc", Bindable.of(JdbcSessionProperties.class)).get();
        assertThat(properties.getSchema()).isEqualTo("classpath:db/session/schema-postgresql.sql");
        assertThat(properties.getInitializeSchema()).isEqualTo(DatabaseInitializationMode.ALWAYS);
        assertThat(properties.isContinueOnError()).isFalse();
    }

    @Test
    void freshAndRepeatedInitializationPreservesSessionDataAndRecreatesMissingIndexes() throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank());
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "session_init_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("create schema " + schema);
                sql.execute("set local search_path to " + schema);
                var resource = new ClassPathResource("db/session/schema-postgresql.sql");
                ScriptUtils.executeSqlScript(connection, resource);
                sql.execute("""
                        insert into spring_session values('11111111-1111-1111-1111-111111111111',
                          '22222222-2222-2222-2222-222222222222',1000,1000,7200,7201000,'client@example.ru');
                        insert into spring_session_attributes values('11111111-1111-1111-1111-111111111111',
                          'SPRING_SECURITY_CONTEXT',decode('010203','hex'));
                        drop index spring_session_ix2;
                        """);
                ScriptUtils.executeSqlScript(connection, resource);
                ScriptUtils.executeSqlScript(connection, resource);
                try (var result = sql.executeQuery("select principal_name,encode(attribute_bytes,'hex') from spring_session s join spring_session_attributes a on a.session_primary_id=s.primary_id")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("client@example.ru");
                    assertThat(result.getString(2)).isEqualTo("010203");
                    assertThat(result.next()).isFalse();
                }
                try (var result = sql.executeQuery("select count(*) from pg_indexes where schemaname=current_schema() and indexname like 'spring_session_ix%'")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(3);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}

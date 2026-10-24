package ru.ravel.ItDesk.migration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PortalNotificationMigrationTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void defaultsOldAccountsToOptOutAndPreservesExistingPreferences(boolean columnAlreadyExists) throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank());
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "portal_notifications_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("create schema " + schema);
                sql.execute("set local search_path to " + schema);
                sql.execute("create table user_t(id bigint primary key)");
                sql.execute("insert into user_t values(1),(2)");
                if (columnAlreadyExists) {
                    sql.execute("alter table user_t add column notify_portal_messages boolean");
                    sql.execute("update user_t set notify_portal_messages=true where id=2");
                }
                try (var resource = getClass().getResourceAsStream("/db/migration/V72__portal_message_notifications.sql")) {
                    String migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                    sql.execute(migration);
                    sql.execute(migration);
                }
                try (var rows = sql.executeQuery("select notify_portal_messages from user_t order by id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isFalse();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isEqualTo(columnAlreadyExists);
                }
                sql.execute("insert into user_t(id) values(3)");
                try (var rows = sql.executeQuery("select notify_portal_messages from user_t where id=3")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(1)).isEqualTo(false);
                }
            } finally {
                connection.rollback();
            }
        }
    }
}

package ru.ravel.ItDesk.migration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.jpa.repository.Query;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TaskFirstOpenMigrationTest {
    @ParameterizedTest
    @ValueSource(strings = {"fresh", "hibernate", "existing_fk"})
    void firstOpeningSurvivesUpdatesAndIsSpecificToUser(String schemaVariant) throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL to run PostgreSQL migration tests");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "task_opening_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("SET LOCAL search_path TO " + schema);
                sql.execute("""
                        CREATE TABLE user_t(id bigint PRIMARY KEY);
                        CREATE TABLE task(id bigint PRIMARY KEY, executor_id bigint REFERENCES user_t(id),
                            completed boolean, frozen boolean);
                        INSERT INTO user_t VALUES (1), (2);
                        INSERT INTO task VALUES (10, 1, false, false), (20, 2, false, false),
                            (30, 1, true, false), (40, 1, false, true), (50, null, false, false),
                            (60, 1, null, null);
                        """);
                if (!schemaVariant.equals("fresh")) {
                    sql.execute("""
                            CREATE TABLE task_user_open(task_id bigint NOT NULL, user_id bigint NOT NULL,
                                opened_at timestamptz NOT NULL, PRIMARY KEY(task_id, user_id));
                            INSERT INTO task_user_open VALUES (20, 2, '2026-10-18T00:00:00Z');
                            """);
                    if (schemaVariant.equals("existing_fk")) {
                        sql.execute("""
                                ALTER TABLE task_user_open ADD FOREIGN KEY(task_id) REFERENCES task(id);
                                ALTER TABLE task_user_open ADD FOREIGN KEY(user_id) REFERENCES user_t(id);
                                """);
                    }
                }
                String migration;
                try (var resource = Objects.requireNonNull(getClass()
                        .getResourceAsStream("/db/migration/V58__task_first_open.sql"))) {
                    migration = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                }
                sql.execute(migration);
                sql.execute(migration);
                if (!schemaVariant.equals("fresh")) {
                    try (var result = sql.executeQuery("SELECT opened_at FROM task_user_open WHERE task_id = 20 AND user_id = 2")) {
                        assertThat(result.next()).isTrue();
                        assertThat(result.getTimestamp(1).toInstant()).isEqualTo(java.time.Instant.parse("2026-10-18T00:00:00Z"));
                    }
                    // Keep the same starting state for the behavior assertions below.
                    sql.execute("DELETE FROM task_user_open WHERE task_id = 20");
                }
                assertThat(unopened(connection, 1)).isEqualTo(2);
                assertThat(unopened(connection, 2)).isEqualTo(1);

                assertThat(open(connection, 10, 1)).isEqualTo(1);
                assertThat(unopened(connection, 1)).isEqualTo(1);
                var openedAt = timestamp(connection);
                assertThat(open(connection, 10, 1)).isZero();
                assertThat(timestamp(connection)).isEqualTo(openedAt);

                // A new executor has their own opening record; reassignment back keeps the old one.
                sql.execute("UPDATE task SET executor_id = 2 WHERE id = 10");
                assertThat(unopened(connection, 2)).isEqualTo(2);
                assertThat(open(connection, 10, 2)).isEqualTo(1);
                assertThat(unopened(connection, 2)).isEqualTo(1);
                sql.execute("UPDATE task SET executor_id = 1 WHERE id = 10");
                assertThat(unopened(connection, 1)).isEqualTo(1);

                // Opening before assignment also means the user has already seen the task.
                assertThat(open(connection, 50, 1)).isEqualTo(1);
                sql.execute("UPDATE task SET executor_id = 1 WHERE id = 50");
                assertThat(unopened(connection, 1)).isEqualTo(1);

                sql.execute("DELETE FROM task WHERE id = 10");
                try (var result = sql.executeQuery("SELECT count(*) FROM task_user_open WHERE task_id = 10")) {
                    result.next();
                    assertThat(result.getLong(1)).isZero();
                }
                sql.execute("UPDATE task SET executor_id = NULL WHERE executor_id = 1");
                sql.execute("DELETE FROM user_t WHERE id = 1");
                try (var result = sql.executeQuery("SELECT count(*) FROM task_user_open")) {
                    result.next();
                    assertThat(result.getLong(1)).isZero();
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private static int open(Connection connection, long taskId, long userId) throws Exception {
        String query = TaskRepository.class.getMethod("markOpened", Long.class, Long.class)
                .getAnnotation(Query.class).value().replace(":taskId", "?").replace(":userId", "?");
        try (var sql = connection.prepareStatement(query)) {
            sql.setLong(1, taskId);
            sql.setLong(2, userId);
            return sql.executeUpdate();
        }
    }

    private static Object timestamp(Connection connection) throws Exception {
        try (var sql = connection.createStatement();
             var result = sql.executeQuery("SELECT opened_at FROM task_user_open WHERE task_id = 10 AND user_id = 1")) {
            result.next();
            return result.getObject(1);
        }
    }

    private static long unopened(Connection connection, long userId) throws Exception {
        try (var sql = connection.prepareStatement("""
                SELECT count(*) FROM task t WHERE t.executor_id = ?
                  AND coalesce(t.completed, false) = false AND coalesce(t.frozen, false) = false
                  AND NOT EXISTS (SELECT 1 FROM task_user_open o WHERE o.task_id = t.id AND o.user_id = ?)
                """)) {
            sql.setLong(1, userId);
            sql.setLong(2, userId);
            try (var result = sql.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}

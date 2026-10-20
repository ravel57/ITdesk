package ru.ravel.ItDesk.migration;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ClientDeletionMigrationTest {

    // Run against a disposable PostgreSQL database. Each case rolls back its own schema.
    @ParameterizedTest
    @ValueSource(strings = {"hibernate", "cascade", "duplicate", "missing"})
    void deletingClientRemovesAssociationsButPreservesIncidentsAndOtherClients(String schemaVariant) throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL to run PostgreSQL migration tests");
        try (Connection connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                String schema = "client_deletion_" + UUID.randomUUID().toString().replace("-", "");
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("SET LOCAL search_path TO " + schema);
                statement.execute("""
                        CREATE TABLE client(id bigint PRIMARY KEY);
                        CREATE TABLE task(id bigint PRIMARY KEY, client_id bigint REFERENCES client(id));
                        CREATE TABLE support_incident(id bigint PRIMARY KEY);
                        CREATE TABLE support_line(id bigint PRIMARY KEY);
                        CREATE TABLE task_support_line_stage(
                            id bigint PRIMARY KEY, task_id bigint NOT NULL,
                            support_line_id bigint REFERENCES support_line(id));
                        CREATE TABLE support_incident_task_link(
                            id bigint PRIMARY KEY, task_id bigint NOT NULL,
                            incident_id bigint REFERENCES support_incident(id) ON DELETE CASCADE,
                            active boolean NOT NULL);
                        CREATE TABLE support_incident_clients(
                            incident_id bigint REFERENCES support_incident(id) ON DELETE CASCADE,
                            client_id bigint NOT NULL, PRIMARY KEY(incident_id, client_id));
                        CREATE TABLE task_access_users(task_id bigint REFERENCES task(id) ON DELETE CASCADE);
                        CREATE TABLE user_t(id bigint PRIMARY KEY, client_id bigint REFERENCES client(id) ON DELETE SET NULL);
                        """);
                for (String[] relation : new String[][] {
                        {"task_support_line_stage", "task_id", "task", "fk79yyauogi52ru1arer516hx7b"},
                        {"support_incident_task_link", "task_id", "task", "fkcug93eenv6hhs3advu4fsq7wn"},
                        {"support_incident_clients", "client_id", "client", "legacy_client_fk"}
                }) {
                    if (!schemaVariant.equals("missing")) {
                        statement.execute("ALTER TABLE " + relation[0] + " ADD CONSTRAINT " + relation[3]
                                + " FOREIGN KEY (" + relation[1] + ") REFERENCES " + relation[2] + "(id) ON DELETE "
                                + (schemaVariant.equals("cascade") ? "CASCADE" : "NO ACTION"));
                    }
                    if (schemaVariant.equals("duplicate")) {
                        statement.execute("ALTER TABLE " + relation[0] + " ADD FOREIGN KEY (" + relation[1]
                                + ") REFERENCES " + relation[2] + "(id) ON DELETE CASCADE");
                    }
                }
                statement.execute("""
                        INSERT INTO client VALUES (1), (2);
                        INSERT INTO task VALUES (23, 1), (59, 1), (60, 2);
                        INSERT INTO support_incident VALUES (1), (2);
                        INSERT INTO support_line VALUES (1);
                        INSERT INTO task_support_line_stage VALUES (1, 23, 1), (2, 59, 1), (3, 60, 1);
                        INSERT INTO support_incident_task_link VALUES (1, 23, 1, true), (2, 59, 1, false), (3, 60, 1, true);
                        INSERT INTO support_incident_clients VALUES (1, 1), (1, 2), (2, 1);
                        INSERT INTO task_access_users VALUES (23), (59), (60);
                        INSERT INTO user_t VALUES (1, 1), (2, 2);
                        """);
                for (int attempt = 0; attempt < 2; attempt++) {
                    statement.execute(migration("V56__task_support_line_stage_delete_cascade.sql"));
                    statement.execute(migration("V57__incident_client_and_task_delete_cascade.sql"));
                }
                assertThat(count(statement, "task_support_line_stage")).isEqualTo(3);
                assertThat(count(statement, "support_incident_task_link")).isEqualTo(3);
                assertThat(count(statement, "support_incident_clients")).isEqualTo(3);

                var savepoint = connection.setSavepoint();
                SQLException blocked = assertThrows(SQLException.class,
                        () -> statement.execute("DELETE FROM support_line WHERE id = 1"));
                assertThat(blocked.getSQLState()).isEqualTo("23503");
                connection.rollback(savepoint);

                // Match Hibernate's order: orphan tasks first, then their client.
                statement.execute("DELETE FROM task WHERE client_id = 1");
                statement.execute("DELETE FROM client WHERE id = 1");
                assertThat(count(statement, "client WHERE id = 2")).isEqualTo(1);
                assertThat(count(statement, "task WHERE id = 60")).isEqualTo(1);
                assertThat(count(statement, "support_incident")).isEqualTo(2);
                assertThat(count(statement, "task_support_line_stage")).isEqualTo(1);
                assertThat(count(statement, "support_incident_task_link WHERE task_id = 60")).isEqualTo(1);
                assertThat(count(statement, "support_incident_task_link")).isEqualTo(1);
                assertThat(count(statement, "support_incident_clients WHERE client_id = 2")).isEqualTo(1);
                assertThat(count(statement, "support_incident_clients")).isEqualTo(1);
                assertThat(count(statement, "task_access_users")).isEqualTo(1);
                assertThat(count(statement, "user_t WHERE id = 1 AND client_id IS NULL")).isEqualTo(1);
                assertThat(count(statement, "user_t WHERE id = 2 AND client_id = 2")).isEqualTo(1);
            } finally {
                connection.rollback();
            }
        }
    }

    private static String migration(String name) throws Exception {
        try (var resource = Objects.requireNonNull(ClientDeletionMigrationTest.class
                .getResourceAsStream("/db/migration/" + name))) {
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static long count(Statement statement, String tableAndCondition) throws SQLException {
        try (var result = statement.executeQuery("SELECT count(*) FROM " + tableAndCondition)) {
            result.next();
            return result.getLong(1);
        }
    }
}

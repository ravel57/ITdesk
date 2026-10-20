package ru.ravel.ItDesk.migration;

import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.model.MessageFrom;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MessageSourceMigrationTest {
    @Test
    void addsMessageSourcesAndMigratesOnlyPortalCardsWithoutChangingExistingChannels() throws Exception {
        String url = System.getenv("ITDESK_TEST_POSTGRES_URL");
        assumeTrue(url != null && !url.isBlank(), "Set ITDESK_TEST_POSTGRES_URL for PostgreSQL tests");
        assertThat(MessageFrom.PORTAL.ordinal()).isEqualTo(3);
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try (var sql = connection.createStatement()) {
                String schema = "message_source_" + UUID.randomUUID().toString().replace("-", "");
                sql.execute("create schema " + schema);
                sql.execute("set local search_path to " + schema);
                sql.execute("""
                        create table client(id bigint primary key, message_from smallint
                          constraint client_message_from_check check(message_from between 0 and 2));
                        create table user_t(id bigint primary key, client_id bigint);
                        create table user_authorities(user_id bigint, authorities varchar(255));
                        create table message(id bigint primary key);
                        insert into client values (1,0),(2,1),(3,2),(4,null),(5,null),(6,null);
                        insert into user_t values (10,3),(11,4),(12,5);
                        insert into user_authorities values (10,'CLIENT'),(11,'CLIENT'),(12,'OBSERVER');
                        insert into message values (1);
                        """);
                for (String file : new String[]{"V69__message_source.sql", "V70__portal_message_channel.sql"}) {
                    try (var input = getClass().getResourceAsStream("/db/migration/" + file)) {
                        String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                        sql.execute(migration);
                        sql.execute(migration);
                    }
                }
                try (var rows = sql.executeQuery("select message_from from client order by id")) {
                    for (Integer expected : new Integer[]{0, 1, 2, 3, null, null}) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getObject(1, Integer.class)).isEqualTo(expected);
                    }
                }
                sql.execute("insert into client values(7,3)");
                sql.execute("insert into message(id,from_source) values(2,'PORTAL')");
                try (var rows = sql.executeQuery("select from_source from message order by id")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull();
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("PORTAL");
                }
            } finally {
                connection.rollback();
            }
        }
    }
}

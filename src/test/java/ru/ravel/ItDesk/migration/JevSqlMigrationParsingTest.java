package ru.ravel.ItDesk.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.migration.JavaMigration;
import org.flywaydb.core.internal.database.DatabaseTypeRegister;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.scanner.Scanner;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class JevSqlMigrationParsingTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "V61__jev_duplicate_rule_condition_actions.sql",
            "V62__jev_duplicate_rule_explicit_variables.sql",
            "V63__jev_incident_detector_rule_explicit_variables.sql"
    })
    void workflowJsonParsesWithDefaultFlywayPlaceholders(String filename) {
        var configuration = Flyway.configure().locations("classpath:db/migration");
        var scanner = new Scanner<JavaMigration>(JavaMigration.class, configuration, configuration.getLocations());
        var resources = scanner.getResources(filename.substring(0, 5), ".sql");
        assertThat(resources).extracting(resource -> resource.getFilename()).containsExactly(filename);
        var databaseType = DatabaseTypeRegister.getDatabaseTypeForUrl("jdbc:postgresql://localhost/unused", configuration);
        var parser = databaseType.createParser(configuration, scanner, new ParsingContext());

        // Parse the actual SQL with placeholder replacement enabled; extracting JSON alone misses ${ collisions.
        int statements = 0;
        try (var iterator = parser.parse(resources.iterator().next())) {
            while (iterator.hasNext()) {
                assertThat(iterator.next().canExecuteInTransaction()).isTrue();
                statements++;
            }
        }
        assertThat(statements).isGreaterThan(0);
    }
}

package ru.ravel.ItDesk.component;

import lombok.RequiredArgsConstructor;
import org.flywaydb.core.Flyway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class FlywayMigrationRunner implements CommandLineRunner {

	private final Flyway flyway;

	@Override
	public void run(String... args) {
		flyway.migrate();
	}
}
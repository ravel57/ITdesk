package ru.ravel.ItDesk.component;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class FlywayMigrationRunner implements CommandLineRunner {

	private final Flyway flyway;

	@Autowired
	public FlywayMigrationRunner(Flyway flyway) {
		this.flyway = flyway;
	}

	@Override
	public void run(String... args) throws Exception {
		flyway.migrate();
	}
}
package ru.ravel.ItDesk;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ItDeskApplication {

	public static void main(String[] args) {
		SpringApplication.run(ItDeskApplication.class, args);
	}

}

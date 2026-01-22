package ru.ravel.ItDesk.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.concurrent.locks.ReentrantLock;

@Configuration
public class FlywayConfig {


	@Value("${spring.datasource.url}")
	private String url;

	@Value("${spring.datasource.username}")
	private String username;

	@Value("${spring.datasource.password}")
	private String password;

	@Value("${spring.flyway.locations}")
	private String locations;

	@Value("${spring.datasource.driver-class-name}")
	private String driver;

	@Value("${spring.flyway.clean-disabled:true}")
	private boolean cleanDisabled;

	@Bean
	public Flyway flyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations(locations)
				.baselineOnMigrate(true)
				.cleanDisabled(cleanDisabled)
				.load();
	}

	@Bean
	public DataSource dataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName(this.driver);
		dataSource.setUrl(url);
		dataSource.setUsername(username);
		dataSource.setPassword(password);
		return dataSource;
	}
}
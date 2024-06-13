package ru.ravel.ItDesk;

import io.prometheus.client.exporter.HTTPServer;
import io.prometheus.client.hotspot.DefaultExports;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;

@SpringBootApplication
@EnableScheduling
public class ItDeskApplication {

	private HTTPServer server;


	public static void main(String[] args) {
		SpringApplication.run(ItDeskApplication.class, args);
	}


	@PostConstruct
	public void startPrometheusServer() throws IOException {
		DefaultExports.initialize();
		server = new HTTPServer(8085);
	}


	@PreDestroy
	public void stopPrometheusServer() {
		if (server != null) {
			server.close();
		}
	}

}

package ru.ravel.ItDesk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.ravel.ItDesk.dto.PriorityKeyDeserializer;
import ru.ravel.ItDesk.model.Priority;


@Configuration
public class JacksonConfig {
	@Bean
	public ObjectMapper objectMapper() {
		SimpleModule module = new SimpleModule();
		module.addKeyDeserializer(Priority.class, new PriorityKeyDeserializer());
		return JsonMapper.builder()
				.addModule(module)
				.addModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
				.findAndAddModules()
				.build();
	}
}

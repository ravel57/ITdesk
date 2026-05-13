package ru.ravel.ItDesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import ru.ravel.ItDesk.dto.PriorityKeyDeserializer;
import ru.ravel.ItDesk.model.Priority;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

@Configuration
public class JacksonConfig {

	@Bean
	@Primary
	public JsonMapper jsonMapper() {
		SimpleModule module = new SimpleModule();
		module.addKeyDeserializer(Priority.class, new PriorityKeyDeserializer());
		return JsonMapper.builder()
				.addModule(module)
				.findAndAddModules()
				.withCoercionConfig(LogicalType.Collection, config ->
						config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsEmpty)
				)
				.withCoercionConfig(LogicalType.POJO, config ->
						config.setCoercion(CoercionInputShape.EmptyString, CoercionAction.AsNull)
				)
				.build();
	}
}
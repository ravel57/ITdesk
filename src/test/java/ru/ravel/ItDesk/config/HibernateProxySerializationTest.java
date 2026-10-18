package ru.ravel.ItDesk.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Task;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class HibernateProxySerializationTest {

	/** Имитация прокси, который Hibernate подставляет в Task.supportLine. */
	static class SupportLineProxy extends SupportLine implements HibernateProxy {
		private final LazyInitializer initializer = mock(LazyInitializer.class);

		@Override
		public Object writeReplace() {
			return this;
		}

		@Override
		public LazyInitializer getHibernateLazyInitializer() {
			return initializer;
		}
	}

	@Test
	@SuppressWarnings("unchecked")
	void serializesTaskWithProxiedSupportLine() throws Exception {
		ObjectMapper mapper = new LegacyJacksonConfig().legacyObjectMapper();
		SupportLineProxy line = new SupportLineProxy();
		line.setId(1L);
		line.setName("Первая линия");
		Task task = Task.builder().id(14L).name("Задача").supportLine(line).build();

		String json = mapper.writeValueAsString(task);
		assertFalse(json.contains("hibernateLazyInitializer"), json);

		Map<String, Object> dto = mapper.convertValue(task, Map.class);
		assertEquals("Первая линия", ((Map<String, Object>) dto.get("supportLine")).get("name"));
	}
}

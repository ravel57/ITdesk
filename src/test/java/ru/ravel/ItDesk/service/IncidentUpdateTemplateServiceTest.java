package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.model.incident.IncidentUpdateTemplate;
import ru.ravel.ItDesk.repository.IncidentUpdateTemplateRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentUpdateTemplateServiceTest {

	private IncidentUpdateTemplateRepository repository;
	private IncidentUpdateTemplateService service;

	@BeforeEach
	void setUp() {
		repository = mock(IncidentUpdateTemplateRepository.class);
		when(repository.save(any(IncidentUpdateTemplate.class))).thenAnswer(invocation -> invocation.getArgument(0));
		service = new IncidentUpdateTemplateService(repository);
	}

	@Test
	void createTrimsFieldsGeneratesKeyAndAppendsToEnd() {
		when(repository.count()).thenReturn(5L);
		IncidentUpdateTemplate created = service.create(IncidentUpdateTemplate.builder()
				.key("investigating")
				.title("  Плановые работы ")
				.text(" Проводятся плановые работы. ")
				.orderNumber(0)
				.build());

		assertEquals("Плановые работы", created.getTitle());
		assertEquals("Проводятся плановые работы.", created.getText());
		assertEquals(5, created.getOrderNumber());
		assertTrue(created.getKey().startsWith("custom-"), "ключ из запроса не принимается, генерируется новый");
	}

	@Test
	void updateKeepsKeyAndOrder() {
		IncidentUpdateTemplate stored = IncidentUpdateTemplate.builder()
				.id(1L).key("investigating").title("Исследуем").text("Старый текст").orderNumber(0).build();
		when(repository.findById(1L)).thenReturn(Optional.of(stored));

		IncidentUpdateTemplate updated = service.update(1L, IncidentUpdateTemplate.builder()
				.key("other").title("Исследуем").text("Новый текст").orderNumber(7).build());

		assertEquals("investigating", updated.getKey());
		assertEquals(0, updated.getOrderNumber());
		assertEquals("Новый текст", updated.getText());
	}

	@Test
	void rejectsBlankAndDuplicateTitles() {
		assertThrows(IllegalArgumentException.class, () -> service.create(IncidentUpdateTemplate.builder().title(" ").text("x").build()));
		assertThrows(IllegalArgumentException.class, () -> service.create(IncidentUpdateTemplate.builder().title("x").text(" ").build()));

		when(repository.existsByTitleIgnoreCaseAndIdNot(anyString(), anyLong())).thenReturn(true);
		assertThrows(IllegalArgumentException.class, () -> service.create(IncidentUpdateTemplate.builder().title("Исследуем").text("x").build()));
	}

	@Test
	void resortAssignsPositions() {
		IncidentUpdateTemplate first = IncidentUpdateTemplate.builder().id(1L).orderNumber(0).build();
		IncidentUpdateTemplate second = IncidentUpdateTemplate.builder().id(2L).orderNumber(1).build();
		when(repository.findById(1L)).thenReturn(Optional.of(first));
		when(repository.findById(2L)).thenReturn(Optional.of(second));

		service.resort(List.of(2L, 1L));

		assertEquals(0, second.getOrderNumber());
		assertEquals(1, first.getOrderNumber());
	}
}

package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.jev.JevDecision;
import ru.ravel.ItDesk.repository.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JevDecisionObjectServiceTest {

	@Test
	void parsesReferencesFromContext() {
		assertEquals(List.of(new JevDecisionObjectService.Reference("client", 1L), new JevDecisionObjectService.Reference("message", 124L)),
				JevDecisionObjectService.references("client=1, message=124"));
		assertEquals(List.of(new JevDecisionObjectService.Reference("task", 31L)), JevDecisionObjectService.references("task=31"));
		assertTrue(JevDecisionObjectService.references("subtask=5").isEmpty());
		assertTrue(JevDecisionObjectService.references(null).isEmpty());
	}

	@Test
	void labelsAndPreviewUseRealObjects() {
		ClientRepository clients = mock(ClientRepository.class);
		MessageRepository messages = mock(MessageRepository.class);
		JevDecisionObjectService service = new JevDecisionObjectService(clients, messages, mock(TaskRepository.class),
				mock(IncidentRepository.class), mock(ServiceRepository.class));
		Client client = Client.builder().id(1L).firstname("Иван").lastname("Петров").build();
		Message message = Message.builder().id(124L).text("1с не подключается\nи база данных сломалась").build();
		when(clients.findAllById(any())).thenReturn(List.of(client));
		when(messages.findAllById(any())).thenReturn(List.of(message));
		when(messages.findById(124L)).thenReturn(Optional.of(message));

		Map<String, String> labels = service.labels(List.of(JevDecision.builder().context("client=1, message=124").build()));
		assertEquals("Иван Петров", labels.get("client:1"));
		assertEquals("«1с не подключается и база данных сломалась»", labels.get("message:124"));

		Map<String, Object> preview = service.preview("message", 124L, 1L).orElseThrow();
		assertEquals("/chats/1", preview.get("link"));
		assertEquals("1с не подключается\nи база данных сломалась", preview.get("text"));
		assertTrue(service.preview("message", 999L, null).isEmpty());
	}
}

package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.dto.AnswerRequired;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.repository.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Автоматическая метка «требует ответа» не переносится внутри цепочки входящих сообщений,
 * чтобы не сбрасывать таймер ожидания ответа.
 */
class ClientAnswerRequiredChainTest {

	private final ZonedDateTime start = ZonedDateTime.now().minusHours(1);
	private Client client;
	private ClientService service;

	@BeforeEach
	void setUp() {
		client = Client.builder().id(1L).messages(new ArrayList<>()).build();
		ClientRepository clientRepository = mock(ClientRepository.class);
		when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
		when(clientRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		service = new ClientService(clientRepository, mock(TaskRepository.class), mock(MessageRepository.class),
				mock(TelegramService.class), mock(UserService.class), mock(OrganizationService.class),
				mock(SlaRepository.class), mock(WebSocketService.class), mock(EventPublisher.class),
				mock(UserRepository.class), mock(GlobalSearchService.class), mock(SlaPauseRepository.class),
				mock(UserNotificationService.class), mock(PendingFileUploadRegistry.class));
	}

	@Test
	void flagStaysOnFirstMessageOfChain() {
		incoming(1);
		assertEquals(Optional.empty(), service.markAnswerRequiredForSystem(1L, 1L));
		incoming(2);
		assertEquals(Optional.of(1L), service.markAnswerRequiredForSystem(1L, 2L));
		incoming(3);
		assertEquals(Optional.of(1L), service.markAnswerRequiredForSystem(1L, 3L));

		assertEquals(AnswerRequired.ANSWER_REQUIRED, message(1).getAnswerRequired());
		assertEquals(AnswerRequired.NOT_SET, message(2).getAnswerRequired());
		assertEquals(AnswerRequired.NOT_SET, message(3).getAnswerRequired());
		assertEquals(1L, client.getFirstUnansweredMessageId(), "таймер считается от первого сообщения");
	}

	@Test
	void notRequiredBreaksChainAndNextMessageGetsFlag() {
		incoming(1);
		service.markAnswerRequiredForSystem(1L, 1L);
		incoming(2);
		service.answerRequiredForSystem(1L, 2L, AnswerRequired.ANSWER_NOT_REQUIRED); // «спасибо, заработало»
		incoming(3);
		assertEquals(Optional.empty(), service.markAnswerRequiredForSystem(1L, 3L));

		assertEquals(AnswerRequired.NOT_SET, message(1).getAnswerRequired());
		assertEquals(AnswerRequired.ANSWER_NOT_REQUIRED, message(2).getAnswerRequired());
		assertEquals(AnswerRequired.ANSWER_REQUIRED, message(3).getAnswerRequired());
		assertEquals(3L, client.getFirstUnansweredMessageId());
	}

	@Test
	void afterNotRequiredFlagStaysOnFirstMessageOfNewChain() {
		incoming(1);
		service.answerRequiredForSystem(1L, 1L, AnswerRequired.ANSWER_NOT_REQUIRED);
		incoming(2);
		assertEquals(Optional.empty(), service.markAnswerRequiredForSystem(1L, 2L));
		incoming(3);
		assertEquals(Optional.of(2L), service.markAnswerRequiredForSystem(1L, 3L));
		assertEquals(AnswerRequired.ANSWER_NOT_REQUIRED, message(1).getAnswerRequired());
		assertEquals(AnswerRequired.ANSWER_REQUIRED, message(2).getAnswerRequired());
		assertEquals(AnswerRequired.NOT_SET, message(3).getAnswerRequired());
		assertEquals(2L, client.getFirstUnansweredMessageId());
	}

	@Test
	void operatorReplyStartsNewChain() {
		incoming(1);
		service.markAnswerRequiredForSystem(1L, 1L);
		outgoing(2);
		incoming(3);
		assertEquals(Optional.empty(), service.markAnswerRequiredForSystem(1L, 3L));
		assertEquals(AnswerRequired.ANSWER_REQUIRED, message(3).getAnswerRequired());
		assertTrue(client.getFirstUnansweredMessageId() == 3L);
	}

	private void incoming(long id) {
		client.getMessages().add(Message.builder().id(id).text("Пропала сеть").isSent(false).isComment(false)
				.deleted(false).date(start.plusMinutes(id)).build());
	}

	private void outgoing(long id) {
		client.getMessages().add(Message.builder().id(id).text("Проверяем").isSent(true).isComment(false)
				.deleted(false).date(start.plusMinutes(id)).build());
	}

	private Message message(long id) {
		return client.getMessages().stream().filter(m -> m.getId() == id).findFirst().orElseThrow();
	}
}

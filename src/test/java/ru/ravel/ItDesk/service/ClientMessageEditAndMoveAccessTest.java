package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Редактирование сообщений чата: только исходящие сообщения поддержки и комментарии, автором
 * или ADMIN/MANAGER. Перенос клиента в организацию: только в доступную пользователю.
 */
class ClientMessageEditAndMoveAccessTest {

	private final Organization ownOrganization = Organization.builder().id(5L).name("Своя").build();
	private final Organization foreignOrganization = Organization.builder().id(6L).name("Чужая").build();

	private final User author = user(1L, Role.OPERATOR, ownOrganization);
	private final User otherOperator = user(2L, Role.OPERATOR, ownOrganization);
	private final User manager = user(3L, Role.MANAGER);
	private final User admin = user(4L, Role.ADMIN);

	private UserService userService;
	private MessageRepository messageRepository;
	private ClientRepository clientRepository;
	private OrganizationService organizationService;
	private Client client;

	@BeforeEach
	void setUp() {
		userService = mock(UserService.class, CALLS_REAL_METHODS);
		messageRepository = mock(MessageRepository.class);
		when(messageRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
		clientRepository = mock(ClientRepository.class);
		organizationService = mock(OrganizationService.class);
		when(organizationService.getOrganizations()).thenReturn(List.of(ownOrganization, foreignOrganization));
		client = Client.builder().id(20L).organization(ownOrganization).messages(new ArrayList<>()).build();
		when(clientRepository.findById(20L)).thenReturn(Optional.of(client));
	}

	@Test
	void incomingClientMessageCannotBeEditedEvenByAdmin() {
		Message incoming = addMessage(Message.builder().id(100L).text("от клиента").isSent(false).isComment(false).build());

		loginAs(admin);
		assertThrows(AccessDeniedException.class, () -> edit(incoming));
		assertEquals("от клиента", incoming.getText());
	}

	@Test
	void outgoingMessageIsEditableByAuthorOnlyAmongOperators() {
		Message outgoing = addMessage(Message.builder().id(101L).text("ответ").isSent(true).user(author).build());

		loginAs(otherOperator);
		assertThrows(AccessDeniedException.class, () -> edit(outgoing));
		assertEquals("ответ", outgoing.getText());

		loginAs(author);
		assertEquals("исправлено", edit(outgoing).getText());
	}

	@Test
	void commentIsEditableByAuthorAndByManagerOrAdmin() {
		Message comment = addMessage(Message.builder().id(102L).text("заметка").isComment(true).user(author).build());

		loginAs(otherOperator);
		assertThrows(AccessDeniedException.class, () -> edit(comment));

		loginAs(manager);
		assertEquals("исправлено", edit(comment).getText());

		comment.setText("заметка");
		loginAs(admin);
		assertEquals("исправлено", edit(comment).getText());
	}

	@Test
	void outgoingMessageWithoutAuthorIsEditableOnlyByManagerOrAdmin() {
		Message botMessage = addMessage(Message.builder().id(103L).text("авто").isSent(true).build());

		loginAs(author);
		assertThrows(AccessDeniedException.class, () -> edit(botMessage));

		loginAs(manager);
		assertEquals("исправлено", edit(botMessage).getText());
	}

	@Test
	void restrictedOperatorCannotMoveClientToForeignOrganization() {
		loginAs(author);

		assertThrows(AccessDeniedException.class, () -> clientService().updateClient(20L, payload("Чужая")));
		verify(clientRepository, never()).save(any());

		assertThrows(AccessDeniedException.class, () -> clientService().updateClient(20L, payload(null)));
		verify(clientRepository, never()).save(any());

		clientService().updateClient(20L, payload("Своя"));
		assertSame(ownOrganization, client.getOrganization());
		verify(clientRepository).save(client);
	}

	@Test
	void managerCanMoveClientAnywhere() {
		loginAs(manager);

		clientService().updateClient(20L, payload("Чужая"));

		assertSame(foreignOrganization, client.getOrganization());
	}


	private Message edit(Message message) {
		return clientService().editMessage(20L, message.getId(), Message.builder().text("исправлено").build());
	}

	private Message addMessage(Message message) {
		client.getMessages().add(message);
		when(messageRepository.findById(message.getId())).thenReturn(Optional.of(message));
		return message;
	}

	private Map<String, Object> payload(String organizationName) {
		Map<String, Object> payload = new HashMap<>();
		payload.put("firstname", "Иван");
		payload.put("organization", organizationName);
		return payload;
	}

	private ClientService clientService() {
		return new ClientService(clientRepository, mock(TaskRepository.class), messageRepository,
				mock(TelegramService.class), userService, organizationService,
				mock(SlaRepository.class), mock(WebSocketService.class), mock(EventPublisher.class),
				mock(UserRepository.class), mock(GlobalSearchService.class), mock(SlaPauseRepository.class),
				mock(UserNotificationService.class), mock(PendingFileUploadRegistry.class));
	}

	private void loginAs(User user) {
		doReturn(user).when(userService).getCurrentUser();
	}

	private User user(Long id, Role role, Organization... organizations) {
		User user = new User();
		user.setId(id);
		user.setUsername("user" + id);
		user.setAuthorities(List.of(role));
		user.setAvailableOrganizations(List.of(organizations));
		return user;
	}
}

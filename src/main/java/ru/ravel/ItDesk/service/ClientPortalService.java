package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.dto.ClientPortalMessageDto;
import ru.ravel.ItDesk.dto.ClientPortalMessageRequest;
import ru.ravel.ItDesk.dto.ClientPortalPage;
import ru.ravel.ItDesk.dto.ClientPortalProfileDto;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.MessageRepository;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class ClientPortalService {

	private static final int PAGE_LIMIT = 50;

	private final UserService userService;
	private final ClientRepository clientRepository;
	private final MessageRepository messageRepository;
	private final GlobalSearchService globalSearchService;
	private final EventPublisher eventPublisher;
	private final WebSocketService webSocketService;


	@Transactional(readOnly = true)
	public ClientPortalProfileDto getProfile() {
		User user = requirePortalUser();
		Client client = requireCurrentClient(user);
		Organization organization = client.getOrganization();
		return new ClientPortalProfileDto(
				user.getId(),
				user.getUsername(),
				user.getFirstname(),
				user.getLastname(),
				client.getId(),
				getClientName(client),
				organization == null ? null : organization.getId(),
				organization == null ? null : organization.getName()
		);
	}


	@Transactional(readOnly = true)
	public ClientPortalPage getMessages(Integer page) {
		User user = requirePortalUser();
		Client client = requireCurrentClient(user);
		int safePage = Math.max(1, Objects.requireNonNullElse(page, 1));
		List<Message> loadedDesc = messageRepository.findPortalMessagesPageFromEnd(
				client.getId(),
				PageRequest.of(safePage - 1, PAGE_LIMIT + 1)
		);
		boolean hasOlderMessages = loadedDesc.size() > PAGE_LIMIT;
		String clientName = getClientName(client);
		List<ClientPortalMessageDto> messages = loadedDesc.stream()
				.limit(PAGE_LIMIT)
				.sorted(Comparator
						.comparing(Message::getDate, Comparator.nullsFirst(Comparator.naturalOrder()))
						.thenComparing(Message::getId, Comparator.nullsFirst(Comparator.naturalOrder())))
				.map(message -> ClientPortalMessageDto.from(message, clientName))
				.toList();
		return new ClientPortalPage(messages, !hasOlderMessages);
	}


	@Transactional
	public ClientPortalMessageDto sendMessage(ClientPortalMessageRequest request) {
		if (request == null) {
			throw new IllegalArgumentException("Сообщение не задано");
		}
		String text = Objects.toString(request.getText(), "").trim();
		String fileUuid = Objects.toString(request.getFileUuid(), "").trim();
		if (text.isBlank() && fileUuid.isBlank()) {
			throw new IllegalArgumentException("Введите сообщение или прикрепите файл");
		}

		User user = requirePortalUser();
		Client client = requireCurrentClient(user);
		Message message = Message.builder()
				.text(text)
				.date(ZonedDateTime.now())
				.user(null)
				.isRead(false)
				.isSent(false)
				.isComment(false)
				.deleted(false)
				.fileName(normalizeNullable(request.getFileName()))
				.fileType(normalizeNullable(request.getFileType()))
				.fileUuid(fileUuid.isBlank() ? null : fileUuid)
				.replyMessageId(request.getReplyMessageId())
				.build();

		Message savedMessage = messageRepository.saveAndFlush(message);

		// Message is saved before it is attached to Client. Relying only on
		// client.getMessages().add(...) may leave message.client_id null, so the
		// message is visible through WebSocket but disappears after page reload.
		// Persist the owning FK explicitly, then reload the client from the DB.
		messageRepository.attachMessageToClient(client.getId(), savedMessage.getId());
		Client savedClient = clientRepository.findById(client.getId())
				.orElseThrow(() -> new AccessDeniedException("Карточка клиента не найдена"));

		globalSearchService.indexClient(savedClient);
		globalSearchService.indexClientMessage(savedClient, savedMessage);
		eventPublisher.publish(TriggerType.MESSAGE_INCOMING, Map.of(
				"message", savedMessage,
				"client", savedClient,
				"portalUser", user
		));
		webSocketService.sendNewMessages(new ClientMessage(savedClient, savedMessage));
		return ClientPortalMessageDto.from(savedMessage, getClientName(savedClient));
	}


	private User requirePortalUser() {
		User user = userService.getCurrentUser();
		if (!userService.isClient(user)) {
			throw new AccessDeniedException("Доступ разрешён только клиентам");
		}
		return user;
	}


	private Client requireCurrentClient(User user) {
		if (user.getClientId() == null) {
			throw new AccessDeniedException("Учётная запись не привязана к клиенту");
		}
		Client client = clientRepository.findById(user.getClientId())
				.orElseThrow(() -> new AccessDeniedException("Карточка клиента не найдена"));
		userService.assertUserCanAccessClient(user, client);
		return client;
	}


	private String getClientName(Client client) {
		String name = ("%s %s".formatted(Objects.toString(client.getFirstname(), ""), Objects.toString(client.getLastname(), ""))).trim();
		return name.isBlank() ? "Клиент" : name;
	}


	private String normalizeNullable(String value) {
		String normalized = Objects.toString(value, "").trim();
		return normalized.isBlank() ? null : normalized;
	}
}

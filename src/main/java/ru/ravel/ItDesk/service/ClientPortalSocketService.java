package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientPortalMessageDto;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.Objects;


@Service
@RequiredArgsConstructor
public class ClientPortalSocketService {

	private static final String MESSAGE_DESTINATION = "/queue/client-portal-messages";
	private static final String MESSAGE_DELETED_DESTINATION = "/queue/client-portal-message-deleted";

	private final SimpMessagingTemplate simpMessaging;
	private final UserRepository userRepository;


	public void sendMessage(Client client, Message message) {
		if (client == null || client.getId() == null || message == null) {
			return;
		}
		if (Boolean.TRUE.equals(message.getIsComment()) || Boolean.TRUE.equals(message.getDeleted())) {
			return;
		}
		ClientPortalMessageDto payload = ClientPortalMessageDto.from(message, getClientName(client));
		userRepository.findAllByClientId(client.getId()).stream()
				.filter(Objects::nonNull)
				.filter(User::isEnabled)
				.filter(this::isClientPortalUser)
				.map(User::getUsername)
				.map(String::trim)
				.filter(username -> !username.isBlank())
				.distinct()
				.forEach(username -> simpMessaging.convertAndSendToUser(
						username,
						MESSAGE_DESTINATION,
						payload
				));
	}


	public void sendMessageDeleted(Client client, Long messageId) {
		if (client == null || client.getId() == null || messageId == null) {
			return;
		}
		userRepository.findAllByClientId(client.getId()).stream()
				.filter(Objects::nonNull)
				.filter(User::isEnabled)
				.filter(this::isClientPortalUser)
				.map(User::getUsername)
				.map(String::trim)
				.filter(username -> !username.isBlank())
				.distinct()
				.forEach(username -> simpMessaging.convertAndSendToUser(
						username,
						MESSAGE_DELETED_DESTINATION,
						messageId
				));
	}


	private boolean isClientPortalUser(User user) {
		user.getAuthorities();
		return user.getAuthorities().stream()
						.anyMatch(authority -> Role.CLIENT.getAuthority().equals(authority.getAuthority()));
	}


	private String getClientName(Client client) {
		String name = ("%s %s".formatted(Objects.toString(client.getFirstname(), ""), Objects.toString(client.getLastname(), ""))).trim();
		return name.isBlank() ? "Клиент" : name;
	}

}

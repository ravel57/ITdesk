package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import ru.ravel.ItDesk.dto.ClientUser;
import ru.ravel.ItDesk.dto.ClientUserText;
import ru.ravel.ItDesk.dto.UserOnlineDto;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.service.ClientService;
import ru.ravel.ItDesk.service.UserService;
import ru.ravel.ItDesk.service.WebSocketService;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
public class WebSocketController {

	private final ClientService clientService;
	private final UserService userService;
	private final WebSocketService webSocketService;


	@MessageMapping("/mark-read")
	public void markRead(ClientUser clientUser, Principal principal) {
		User currentUser = authenticatedUser(principal);
		Client client = clientService.markReadAndReturnClient(clientUser, currentUser);
		if (client != null) {
			webSocketService.markRead(client);
		}
	}


	@MessageMapping("/user-online")
	public void userOnline(UserOnlineDto ignoredPayload, Principal principal) {
		User currentUser = authenticatedUser(principal);
		userService.userOnline(currentUser.getId(), currentUser.getUsername());
	}


	@MessageMapping("/typing")
	public void typing(@NotNull ClientUserText clientUserText, Principal principal) {
		clientService.typing(clientUserText, authenticatedUser(principal));
	}


	private User authenticatedUser(Principal principal) {
		if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
			throw new AccessDeniedException("WebSocket user is not authenticated");
		}
		return userService.getUserByUsername(principal.getName());
	}
}
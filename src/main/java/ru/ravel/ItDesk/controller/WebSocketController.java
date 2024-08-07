package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import ru.ravel.ItDesk.dto.ClientUser;
import ru.ravel.ItDesk.dto.ClientUserText;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.service.ClientService;
import ru.ravel.ItDesk.service.UserService;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class WebSocketController {

	private final ClientService clientService;
	private final UserService userService;


	@MessageMapping("/mark-read")
	@SendTo("/topic/mark-read/")
	public Client markRead(ClientUser clientUser) {
		return clientService.markReadAndReturnClient(clientUser);
	}


	@MessageMapping("/user-online")
	public void userOnline(User user) {
		userService.userOnline(user);
	}


	@MessageMapping("/typing")
	public void typing(@NotNull ClientUserText clientUserText) {
		clientService.typing(clientUserText);
	}


	// FIXME TODO
	@MessageMapping("/observer")
	@SendTo("/topic/clients-for-observer/")
	public List<Client> getClientsForObserver(String username) {
		User user = userService.getUsers().stream()
				.filter(it -> it.getUsername().equals(username))
				.findFirst()
				.orElseThrow();
		return clientService.getClientsForObserver(user);
	}

}
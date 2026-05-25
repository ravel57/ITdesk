package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.service.ClientService;
import ru.ravel.ItDesk.service.UserService;
import ru.ravel.ItDesk.service.WebSocketService;

import java.security.Principal;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class ClientsSocketController {

	private final ClientService clientService;
	private final UserService userService;
	private final WebSocketService webSocketService;


	@MessageMapping("/clients/refresh")
	public void refreshClients(Principal principal) {
		if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
			return;
		}

		User user = userService.getUserByUsername(principal.getName());
		List<Client> clients = userService.filterClientsByUser(
				clientService.getClientsForSystem(),
				user
		);

		webSocketService.sendClientsToUser(principal.getName(), clients);
	}
}
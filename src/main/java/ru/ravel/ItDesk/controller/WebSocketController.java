package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.service.ClientService;

@Controller
@RequiredArgsConstructor
public class WebSocketController {

	private final ClientService clientService;

	@MessageMapping("/mark-read")
	@SendTo("/topic/mark-read")
	public Client markRead(String clientId) {
		return clientService.markRead(Long.valueOf(clientId));
	}

}
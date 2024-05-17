package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;
import ru.ravel.ItDesk.dto.ClientUserText;
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


	@MessageMapping("/typing")
	public void typing(@NotNull ClientUserText clientUserText) {
		clientService.typing(clientUserText);
	}

}
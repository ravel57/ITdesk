package ru.ravel.ItDesk.service;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;

import java.util.List;

@Service
public class WebSocketService {

	private final SimpMessagingTemplate simpMessaging;

	public WebSocketService(SimpMessagingTemplate simpMessaging) {
		this.simpMessaging = simpMessaging;
	}

	public void sendClients(List<Client> clients) {
 		simpMessaging.convertAndSend("/topic/clients/", clients);
	}

}

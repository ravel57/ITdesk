package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.User;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WebSocketService {

	private final SimpMessagingTemplate simpMessaging;


	public void sendClients(List<Client> clients) {
 		simpMessaging.convertAndSend("/topic/clients/", clients);
	}


	public void getAuthenticatedUsers(Set<User> users) {
 		simpMessaging.convertAndSend("/topic/authenticated-users/", users);
	}


	public void sendClientsForObserver(List<Client> clients) {
 		simpMessaging.convertAndSend("/topic/clients-for-observer/", clients);
	}

}

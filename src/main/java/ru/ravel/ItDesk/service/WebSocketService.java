package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.ClientMessage;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.GlobalNotification;
import ru.ravel.ItDesk.model.Message;
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


	public void sendNewMessages(ClientMessage clientMessage) {
		simpMessaging.convertAndSend("/topic/client-messages/", clientMessage);
	}


	public void supportMessages(List<Message> supportMessages) {
		simpMessaging.convertAndSend("/topic/support-messages/", supportMessages);
	}


	public void globalNotification(GlobalNotification globalNotification) {
		simpMessaging.convertAndSend("/topic/global-notifications/", globalNotification);
	}

}

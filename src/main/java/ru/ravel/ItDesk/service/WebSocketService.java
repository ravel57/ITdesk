package ru.ravel.ItDesk.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WebSocketService {

	@Autowired
	private SimpMessagingTemplate simpMessaging;

	public void sendOrdersProgress(List<Object> message) {
		simpMessaging.convertAndSend("/topic/ordersInWork/", message);
	}

	public void sendOrdersCollection(Object message) {
		simpMessaging.convertAndSend("/topic/orders/", message);
	}
}

package ru.ravel.ItDesk.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import ru.ravel.ItDesk.service.UserSessionService;

import java.security.Principal;


@Component
@RequiredArgsConstructor
public class OnlineUserWebSocketListener {

	private final UserSessionService userSessionService;


	@EventListener
	public void onConnect(SessionConnectEvent event) {
		StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
		Principal user = accessor.getUser();
		String webSocketSessionId = accessor.getSessionId();
		if (user == null || webSocketSessionId == null) {
			return;
		}
		userSessionService.registerWebSocket(user.getName(), webSocketSessionId);
	}


	@EventListener
	public void onDisconnect(SessionDisconnectEvent event) {
		Principal user = event.getUser();
		if (user == null) {
			return;
		}
		userSessionService.unregisterWebSocket(user.getName(), event.getSessionId());
	}
}
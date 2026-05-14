package ru.ravel.ItDesk.service;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Service
@RequiredArgsConstructor
public class UserSessionService {

	private final WebSocketService webSocketService;

	private final Map<String, String> activeSessionsByUsername = new ConcurrentHashMap<>();


	public void registerLogin(String username, HttpSession session) {
		if (username == null || username.isBlank() || session == null) {
			return;
		}
		String newSessionId = session.getId();
		String oldSessionId = activeSessionsByUsername.put(username, newSessionId);
		if (oldSessionId != null && !oldSessionId.equals(newSessionId)) {
			webSocketService.forceLogout(username, newSessionId);
		}
	}


	public boolean isCurrentSession(String username, String sessionId) {
		if (username == null || sessionId == null) {
			return false;
		}
		String currentSessionId = activeSessionsByUsername.get(username);
		if (currentSessionId == null) {
			activeSessionsByUsername.put(username, sessionId);
			return true;
		}
		return sessionId.equals(currentSessionId);
	}

}
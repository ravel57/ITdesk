package ru.ravel.ItDesk.service;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Service
@RequiredArgsConstructor
public class UserSessionService {

	private final WebSocketService webSocketService;
	private final UserService userService;

	private final Map<String, String> activeSessionsByUsername = new ConcurrentHashMap<>();
	private final Map<String, String> usernameByHttpSessionId = new ConcurrentHashMap<>();
	private final Map<String, Set<String>> webSocketSessionsByUsername = new ConcurrentHashMap<>();


	public void registerLogin(String username, HttpSession session) {
		if (username == null || username.isBlank() || session == null) {
			return;
		}
		String newSessionId = session.getId();
		String oldSessionId = activeSessionsByUsername.put(username, newSessionId);
		usernameByHttpSessionId.put(newSessionId, username);
		userService.userOnline(null, username);
		if (oldSessionId != null && !oldSessionId.equals(newSessionId)) {
			usernameByHttpSessionId.remove(oldSessionId);
			webSocketSessionsByUsername.remove(username);
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
			usernameByHttpSessionId.put(sessionId, username);
			userService.userOnline(null, username);
			return true;
		}
		return sessionId.equals(currentSessionId);
	}


	public void registerWebSocket(String username, String webSocketSessionId) {
		if (username == null || username.isBlank() || webSocketSessionId == null || webSocketSessionId.isBlank()) {
			return;
		}
		webSocketSessionsByUsername
				.computeIfAbsent(username, key -> ConcurrentHashMap.newKeySet())
				.add(webSocketSessionId);
		userService.userOnline(null, username);
	}


	public void unregisterWebSocket(String username, String webSocketSessionId) {
		if (username == null || username.isBlank() || webSocketSessionId == null || webSocketSessionId.isBlank()) {
			return;
		}
		Set<String> sessions = webSocketSessionsByUsername.get(username);
		if (sessions == null) {
			return;
		}
		sessions.remove(webSocketSessionId);
		if (sessions.isEmpty()) {
			webSocketSessionsByUsername.remove(username);
			userService.userOffline(username);
		}
	}


	public void logout(String username, String httpSessionId) {
		if (username == null || username.isBlank()) {
			return;
		}
		if (httpSessionId != null) {
			activeSessionsByUsername.remove(username, httpSessionId);
			usernameByHttpSessionId.remove(httpSessionId, username);
		} else {
			String sessionId = activeSessionsByUsername.remove(username);
			if (sessionId != null) {
				usernameByHttpSessionId.remove(sessionId);
			}
		}
		webSocketSessionsByUsername.remove(username);
		userService.userOffline(username);
	}


	public void sessionDestroyed(String httpSessionId) {
		if (httpSessionId == null || httpSessionId.isBlank()) {
			return;
		}
		String username = usernameByHttpSessionId.remove(httpSessionId);
		if (username == null) {
			return;
		}
		activeSessionsByUsername.remove(username, httpSessionId);
		webSocketSessionsByUsername.remove(username);
		userService.userOffline(username);
	}
}
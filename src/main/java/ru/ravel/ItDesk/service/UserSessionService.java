package ru.ravel.ItDesk.service;

import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Service
@RequiredArgsConstructor
public class UserSessionService {

	private static final String CLIENT_SESSION_MARKER_ATTRIBUTE = UserSessionService.class.getName() + ".clientSessionMarker";
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

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
		String newSessionMarker = getOrCreateClientSessionMarker(session);
		String oldSessionId = activeSessionsByUsername.put(username, newSessionId);
		usernameByHttpSessionId.put(newSessionId, username);
		userService.userOnline(null, username);
		if (oldSessionId != null && !oldSessionId.equals(newSessionId)) {
			usernameByHttpSessionId.remove(oldSessionId);
			webSocketSessionsByUsername.remove(username);
			webSocketService.forceLogout(username, newSessionMarker);
		}
	}


	/**
	 * Returns an opaque browser marker used only to tell a newly logged-in session
	 * from an older tab. It is deliberately NOT the servlet/JSESSIONID value, so
	 * exposing it to JavaScript does not defeat the HttpOnly session cookie.
	 */
	public String getOrCreateClientSessionMarker(HttpSession session) {
		if (session == null) {
			throw new IllegalArgumentException("session is required");
		}
		Object existing = session.getAttribute(CLIENT_SESSION_MARKER_ATTRIBUTE);
		if (existing instanceof String marker && !marker.isBlank()) {
			return marker;
		}
		byte[] random = new byte[24];
		SECURE_RANDOM.nextBytes(random);
		String marker = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
		session.setAttribute(CLIENT_SESSION_MARKER_ATTRIBUTE, marker);
		return marker;
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
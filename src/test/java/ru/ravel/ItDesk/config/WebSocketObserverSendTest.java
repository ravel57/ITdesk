package ru.ravel.ItDesk.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Менеджер организации (OBSERVER) только читает: из /app/** ему доступен лишь запрос списка чатов.
 */
class WebSocketObserverSendTest {

	private final AuthorizationManager<Message<?>> manager =
			new WebSocketSecurityConfig(null).messageAuthorizationManager(MessageMatcherDelegatingAuthorizationManager.builder());

	@Test
	void observerCanOnlyRefreshClients() {
		assertTrue(canSend("ROLE_OBSERVER", "/app/clients/refresh"));
		assertFalse(canSend("ROLE_OBSERVER", "/app/typing"));
		assertFalse(canSend("ROLE_OBSERVER", "/app/user-online"));
		assertFalse(canSend("ROLE_OBSERVER", "/app/mark-read"));
		assertFalse(canSend("ROLE_OBSERVER", "/app/some-future-endpoint"));
	}

	@Test
	void supportKeepsAllAppDestinations() {
		for (String role : new String[]{"ROLE_ADMIN", "ROLE_MANAGER", "ROLE_OPERATOR"}) {
			assertTrue(canSend(role, "/app/clients/refresh"), role);
			assertTrue(canSend(role, "/app/typing"), role);
			assertTrue(canSend(role, "/app/user-online"), role);
			assertTrue(canSend(role, "/app/mark-read"), role);
		}
	}

	@Test
	void clientPortalCannotSendToApp() {
		assertFalse(canSend("ROLE_CLIENT", "/app/clients/refresh"));
		assertFalse(canSend("ROLE_CLIENT", "/app/typing"));
	}


	private boolean canSend(String authority, String destination) {
		return canAccess(authority, destination, SimpMessageType.MESSAGE);
	}

	@Test
	void taskOpeningEventsArePersonalAndStaffOnly() {
		assertTrue(canAccess("ROLE_OPERATOR", "/user/topic/task-opened/", SimpMessageType.SUBSCRIBE));
		assertTrue(canAccess("ROLE_OBSERVER", "/user/topic/task-opened/", SimpMessageType.SUBSCRIBE));
		assertFalse(canAccess("ROLE_CLIENT", "/user/topic/task-opened/", SimpMessageType.SUBSCRIBE));
		assertFalse(canAccess("ROLE_OPERATOR", "/topic/task-opened/", SimpMessageType.SUBSCRIBE));
		assertFalse(canAccess("ROLE_OPERATOR", "/user/another-user/topic/task-opened/", SimpMessageType.SUBSCRIBE));
	}


	private boolean canAccess(String authority, String destination, SimpMessageType type) {
		SimpMessageHeaderAccessor headers = SimpMessageHeaderAccessor.create(type);
		headers.setDestination(destination);
		Message<byte[]> message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
		var authentication = new UsernamePasswordAuthenticationToken(
				"user", null, AuthorityUtils.createAuthorityList(authority));
		return manager.authorize(() -> authentication, message).isGranted();
	}
}

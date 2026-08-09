package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * Transport limits are kept separate from endpoint/security registration.
 * WebSocketSecurityConfig is the single source of truth for STOMP endpoints.
 */
@Configuration
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Override
	public void configureWebSocketTransport(@NotNull WebSocketTransportRegistration registration) {
		registration.setMessageSizeLimit(1_048_576);
		registration.setSendBufferSizeLimit(1_048_576);
		registration.setSendTimeLimit(200_000);
	}
}
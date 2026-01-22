package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

@Configuration
@EnableWebSocketMessageBroker
@CrossOrigin
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Value("${app.allowed-origin:http://localhost:8080}")
	private String allowedOriginPatterns;


	@Override
	public void configureMessageBroker(@NotNull MessageBrokerRegistry config) {
		config.enableSimpleBroker("/topic");
		config.setApplicationDestinationPrefixes("/app");
	}


	@Override
	public void registerStompEndpoints(@NotNull StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").setAllowedOriginPatterns(allowedOriginPatterns).withSockJS();
	}

	@Override
	public void configureWebSocketTransport(@NotNull WebSocketTransportRegistration registration) {
		registration.setMessageSizeLimit(1_048_576);
		registration.setSendBufferSizeLimit(1_048_576);
		registration.setSendTimeLimit(200_000);
	}

}
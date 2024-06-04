package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@CrossOrigin
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

	@Value("${allowed-origin}")
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

}

package ru.ravel.ItDesk.config;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.socket.EnableWebSocketSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.messaging.access.intercept.MessageMatcherDelegatingAuthorizationManager;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import ru.ravel.ItDesk.dto.PriorityKeyDeserializer;
import ru.ravel.ItDesk.model.Priority;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collection;
import java.util.List;


@Configuration
@RequiredArgsConstructor
@EnableWebSocketMessageBroker
@EnableWebSocketSecurity
public class WebSocketSecurityConfig implements WebSocketMessageBrokerConfigurer {

	private final JsonMapper objectMapper;
	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	@Value("${app.allowed-origin:http://localhost:8080}")
	private String allowedOrigins;

	@Bean
	public AuthorizationManager<Message<?>> messageAuthorizationManager(
			MessageMatcherDelegatingAuthorizationManager.Builder messages
	) {
		messages
				.nullDestMatcher().permitAll()
				// OBSERVER is read-only: it may only request its own chat list.
				// typing, user-online, mark-read and any future /app endpoint stay staff-only.
				// Keep this matcher before the generic /app/** rule.
				.simpDestMatchers("/app/clients/refresh").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
				.simpDestMatchers("/app/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/authenticated-users/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/support-messages/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients/**").denyAll()
				.simpSubscribeDestMatchers(
						"/user/topic/client-messages/**",
						"/user/topic/client-message-edited/**",
						"/user/topic/client-message-deleted/**",
						"/user/topic/mark-read/**"
				).hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR")
				.simpSubscribeDestMatchers("/user/topic/clients/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
				.simpSubscribeDestMatchers(
						"/user/topic/task-messages/**",
						"/user/topic/task-updated/**",
						"/user/topic/task-opened/**",
						"/user/topic/incidents-updated/**",
						"/user/topic/user-notification/**",
						"/user/topic/force-logout/**"
				).hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
				.simpSubscribeDestMatchers("/topic/clients-for-observer/**").denyAll()
				.simpSubscribeDestMatchers(
						"/user/topic/observer-client-messages/**",
						"/user/topic/observer-client-message-edited/**",
						"/user/topic/observer-client-message-deleted/**"
				).hasRole("OBSERVER")
				.simpSubscribeDestMatchers(
						"/topic/client-messages/**",
						"/topic/client-message-edited/**",
						"/topic/client-message-deleted/**",
						"/topic/mark-read/**",
						"/topic/task-messages/**",
						"/topic/task-updated/**",
						"/topic/task-opened/**",
						"/topic/incidents-updated/**",
						"/topic/user-notification/**",
						"/topic/force-logout/**"
				).denyAll()
				.simpSubscribeDestMatchers(
						"/user/queue/client-portal-messages",
						"/user/queue/client-portal-message-deleted",
						"/user/queue/client-portal-settings"
				).hasRole("CLIENT")
				.simpSubscribeDestMatchers("/user/**").denyAll()
				.simpSubscribeDestMatchers("/queue/**").denyAll()
				// Клиентский портал не должен иметь доступ к общим операторским топикам.
				.simpSubscribeDestMatchers("/topic/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
				.anyMessage().denyAll();
		return messages.build();
	}



	@Override
	public boolean configureMessageConverters(@NotNull List<MessageConverter> messageConverters) {
		messageConverters.add(new JacksonJsonMessageConverter(objectMapper));
		return false;
	}


	@Override
	public void configureMessageBroker(@NotNull MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
	}


	@Override
	public void registerStompEndpoints(@NotNull StompEndpointRegistry registry) {
		String[] origins = configuredAllowedOrigins();
		registry.addEndpoint("/ws")
				.setAllowedOrigins(origins)
				.withSockJS();
		registry.addEndpoint("/client-portal-ws")
				.setAllowedOrigins(origins)
				.withSockJS();
	}


	private String[] configuredAllowedOrigins() {
		String[] origins = java.util.Arrays.stream(
				allowedOrigins == null ? new String[0] : allowedOrigins.split(",")
		)
				.map(String::trim)
				.filter(origin -> !origin.isBlank())
				.toArray(String[]::new);
		if (origins.length == 0 || java.util.Arrays.asList(origins).contains("*")) {
			throw new IllegalStateException("app.allowed-origin must contain explicit trusted origins");
		}
		return origins;
	}


	@Override
	public void configureClientInboundChannel(@NotNull ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(@NotNull Message<?> message, @NotNull MessageChannel channel) {
				StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
				if (accessor != null) {
					String destination = accessor.getDestination();
					Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
					if (destination != null && authentication != null) {
						Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
						if (logger.isDebugEnabled()) {
							logger.debug("WebSocket destination: {}, authorities: {}", destination, authorities);
						}
					}
				}

				return message;
			}
		});
	}


	@Bean
	public ServletServerContainerFactoryBean createServletServerContainerFactoryBean() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(1048576);
		container.setMaxBinaryMessageBufferSize(1048576);
		return container;
	}

}

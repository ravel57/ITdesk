package ru.ravel.ItDesk.config;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

	@Bean
	public AuthorizationManager<Message<?>> messageAuthorizationManager(
			MessageMatcherDelegatingAuthorizationManager.Builder messages
	) {
		messages
				.nullDestMatcher().permitAll()
				.simpDestMatchers("/app/**").authenticated()
				.simpSubscribeDestMatchers("/topic/authenticated-users/**").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients/**").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients-for-observer/**").hasRole("OBSERVER")
				.simpSubscribeDestMatchers("/user/**").authenticated()
				.simpSubscribeDestMatchers("/queue/**").authenticated()
				.simpSubscribeDestMatchers("/topic/**").authenticated()
				.anyMessage().denyAll();
		return messages.build();
	}


	@Bean(name = "csrfChannelInterceptor")
	public ChannelInterceptor csrfChannelInterceptor() {
		return new ChannelInterceptor() {
		};
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
		registry.addEndpoint("/ws")
				.setAllowedOriginPatterns("*")
				.withSockJS();
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
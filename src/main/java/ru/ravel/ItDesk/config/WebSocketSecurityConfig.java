package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.config.annotation.web.messaging.MessageSecurityMetadataSourceRegistry;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import java.util.Collection;


@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

	Logger logger = LoggerFactory.getLogger(this.getClass());


	@Override
	protected void configureInbound(@NotNull MessageSecurityMetadataSourceRegistry messages) {
		messages.simpDestMatchers("/app/**").authenticated()
				.simpSubscribeDestMatchers("/topic/authenticated-users/").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients/").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients-for-observer/").hasRole("OBSERVER")
				/*.anyMessage().authenticated()*/;
	}


	@Override
	public void configureMessageBroker(@NotNull MessageBrokerRegistry registry) {
		registry.enableSimpleBroker("/topic", "/queue");
		registry.setApplicationDestinationPrefixes("/app");
	}


	@Override
	public void registerStompEndpoints(@NotNull StompEndpointRegistry registry) {
		registry.addEndpoint("/ws").withSockJS();
	}


	@Override
	protected void customizeClientInboundChannel(@NotNull ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(@NotNull Message<?> message, @NotNull MessageChannel channel) {
				StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
				if (accessor != null) {
					String destination = accessor.getDestination();
					Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
					if (destination != null && authentication != null) {
						Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
					}
				}
				return message;
			}
		});
	}


	@Bean
	public ServletServerContainerFactoryBean createServletServerContainerFactoryBean() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(131072);
		container.setMaxBinaryMessageBufferSize(131072);
		return container;
	}


	@Override
	protected boolean sameOriginDisabled() {
		return true;
	}

}

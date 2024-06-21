package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.config.annotation.web.messaging.MessageSecurityMetadataSourceRegistry;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.messaging.simp.config.ChannelRegistration;

import java.util.Collection;


@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

	@Override
	protected void configureInbound(@NotNull MessageSecurityMetadataSourceRegistry messages) {
		messages.simpDestMatchers("/app/**").authenticated()
				.simpSubscribeDestMatchers("/topic/authenticated-users/").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients/").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients-for-observer/").hasAnyRole("OBSERVER")
				/*.anyMessage().denyAll()*/;
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

	@Override
	protected boolean sameOriginDisabled() {
		return true;
	}
}

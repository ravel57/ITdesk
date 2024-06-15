package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.security.config.annotation.web.messaging.MessageSecurityMetadataSourceRegistry;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

	@Override
	protected void configureInbound(@NotNull MessageSecurityMetadataSourceRegistry messages) {
		messages.simpDestMatchers("/app/**").authenticated()
				.simpSubscribeDestMatchers("/topic/authenticated-users/").hasAnyRole("ADMIN", "OPERATOR")
				.simpSubscribeDestMatchers("/topic/clients/").hasAnyRole("ADMIN", "OPERATOR");
//				.anyMessage().denyAll();
	}

	@Override
	protected void customizeClientInboundChannel(@NotNull ChannelRegistration registration) {
		registration.interceptors(new ChannelInterceptor() {
			@Override
			public Message<?> preSend(@NotNull Message<?> message, @NotNull MessageChannel channel) {
				return ChannelInterceptor.super.preSend(message, channel);
			}

			@Override
			public void postSend(@NotNull Message<?> message, @NotNull MessageChannel channel, boolean sent) {
				ChannelInterceptor.super.postSend(message, channel, sent);
			}

			@Override
			public void afterSendCompletion(@NotNull Message<?> message, @NotNull MessageChannel channel, boolean sent, Exception ex) {
				ChannelInterceptor.super.afterSendCompletion(message, channel, sent, ex);
			}

			@Override
			public boolean preReceive(@NotNull MessageChannel channel) {
				return ChannelInterceptor.super.preReceive(channel);
			}

			@Override
			public Message<?> postReceive(@NotNull Message<?> message, @NotNull MessageChannel channel) {
				return ChannelInterceptor.super.postReceive(message, channel);
			}

			@Override
			public void afterReceiveCompletion(Message<?> message, @NotNull MessageChannel channel, Exception ex) {
				ChannelInterceptor.super.afterReceiveCompletion(message, channel, ex);
			}
		});
	}

	@Override
	protected boolean sameOriginDisabled() {
		return true;
	}
}

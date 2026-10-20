package ru.ravel.ItDesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;


/**
 * {@link SessionRegistry} вынесен из {@link WebSecurityConfig} в отдельный конфиг без зависимостей:
 * иначе бины, которым нужен только реестр сессий (например {@code PasswordResetService}), тянут за
 * собой весь конструктор WebSecurityConfig (включая {@link SingleSessionFilter} → {@code UserSessionService}
 * → {@code WebSocketService} → {@code UserService}) и замыкают цикл бинов.
 */
@Configuration
public class SessionRegistryConfig {

	@Bean
	public SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}

}

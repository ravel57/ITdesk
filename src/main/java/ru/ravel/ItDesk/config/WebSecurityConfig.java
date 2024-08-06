package ru.ravel.ItDesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import ru.ravel.ItDesk.service.AuthService;
import ru.ravel.ItDesk.service.UserService;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class WebSecurityConfig {

	private final AuthService authService;
	private final UserService userService;


	WebSecurityConfig(AuthService authService, @Lazy UserService userService) {
		this.authService = authService;
		this.userService = userService;
	}


	@Bean
	UserDetailsService userDetailsService() {
		return authService;
	}


	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}


	@Bean
	SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}


	@Bean
	AuthenticationProvider authenticationProvider() {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
		provider.setUserDetailsService(userDetailsService());
		provider.setPasswordEncoder(passwordEncoder());
		return provider;
	}

	@Bean
	public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
		return authenticationConfiguration.getAuthenticationManager();
	}


	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
		requestCache.setMatchingRequestParameterName(null);
		return http.cors(AbstractHttpConfigurer::disable)
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/js/**", "/css/**", "/icons/**", "/fonts/**").permitAll()
						.requestMatchers("/login", "/api/v1/login").permitAll()
						.requestMatchers("/settings").authenticated()
						.requestMatchers("/settings/profile").authenticated()
						.requestMatchers("/settings/**").hasRole("ADMIN")
						.requestMatchers("/tasks/**").hasAnyRole("ADMIN", "OPERATOR", "OBSERVER")
						.requestMatchers("/ws/**").authenticated()
						.requestMatchers("/actuator/**").permitAll()
						.requestMatchers("/api/v1/support/resave-message").permitAll()
						.anyRequest().authenticated())
				.sessionManagement(sessionManagement -> sessionManagement
						.maximumSessions(1)
						.maxSessionsPreventsLogin(false)
						.sessionRegistry(sessionRegistry())
						.expiredUrl("/session-expired"))
				.formLogin(formLogin -> formLogin
						.loginPage("/login")
						.loginProcessingUrl("/perform_login")
						.defaultSuccessUrl("/chats", true)
						.failureUrl("/login-error")
						.permitAll())
				.logout(logout -> logout
						.logoutSuccessUrl("/logout")
						.invalidateHttpSession(true)
						.deleteCookies("JSESSIONID")
//						.logoutSuccessHandler((request, response, authentication) -> userService.userOffline()) // FIXME
						.permitAll())
				.requestCache(cache -> cache.requestCache(requestCache))
				.build();
	}
}
package ru.ravel.ItDesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
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
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import ru.ravel.ItDesk.service.AuthService;
import ru.ravel.ItDesk.service.UserSessionService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class WebSecurityConfig {

	private final AuthService authService;
//	private final UserService userService;
	private final UserSessionService userSessionService;
	private final SingleSessionFilter singleSessionFilter;


	public WebSecurityConfig(AuthService authService, UserSessionService userSessionService, SingleSessionFilter singleSessionFilter) {
		this.authService = authService;
		this.userSessionService = userSessionService;
		this.singleSessionFilter = singleSessionFilter;
	}


	@Bean
	UserDetailsService userDetailsService() {
		return authService;
	}


	@Bean
	public SessionRegistry sessionRegistry() {
		return new SessionRegistryImpl();
	}


	@Bean
	public AuthenticationProvider authenticationProvider(PasswordEncoder passwordEncoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService());
		provider.setPasswordEncoder(passwordEncoder);
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

		return http
				.cors(AbstractHttpConfigurer::disable)
				.csrf(AbstractHttpConfigurer::disable)
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(
								"/",
								"/index.html",
								"/favicon.ico",
								"/login",
								"/login-error",
								"/session-expired",
								"/assets/**",
								"/js/**",
								"/css/**",
								"/icons/**",
								"/fonts/**",
								"/img/**",
								"/statics/**",
								"/api/v1/login",
								"/api/v1/support/resave-message",
								"/api/v1/support/reset-password",
								"/actuator/**"
						).permitAll()
						.requestMatchers("/chats/**").hasAnyRole("ADMIN", "OPERATOR", "OBSERVER")
						.requestMatchers("/tasks/**").hasAnyRole("ADMIN", "OPERATOR", "OBSERVER")
						.requestMatchers("/settings/profile").authenticated()
						.requestMatchers("/settings/**").hasRole("ADMIN")
						.requestMatchers("/ws/**").authenticated()
						.anyRequest().authenticated())
				.sessionManagement(sessionManagement -> sessionManagement
						.maximumSessions(1)
						.maxSessionsPreventsLogin(false)
						.sessionRegistry(sessionRegistry())
						.expiredUrl("/login?expired"))
				.exceptionHandling(exception -> exception
						.defaultAuthenticationEntryPointFor(
								new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
								PathPatternRequestMatcher.pathPattern("/api/**")
						)
				)
				.formLogin(formLogin -> formLogin
						.loginPage("/login")
						.loginProcessingUrl("/perform_login")
						.successHandler((request, response, authentication) -> {
							userSessionService.registerLogin(
									authentication.getName(),
									request.getSession()
							);
							response.sendRedirect("/chats");
						})
						.failureUrl("/login-error")
						.permitAll())
				.logout(logout -> logout
						.logoutUrl("/logout")
						.addLogoutHandler((request, response, authentication) -> {
							if (authentication == null) {
								return;
							}
							var session = request.getSession(false);
							userSessionService.logout(
									authentication.getName(),
									session != null ? session.getId() : null
							);
						})
						.logoutSuccessUrl("/login")
						.invalidateHttpSession(true)
						.deleteCookies("JSESSIONID")
						.permitAll())
				.requestCache(cache -> cache.requestCache(requestCache))
				.addFilterAfter(singleSessionFilter, UsernamePasswordAuthenticationFilter.class)
				.build();
	}


	@Bean
	public HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}
}
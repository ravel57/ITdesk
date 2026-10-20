package ru.ravel.ItDesk.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import ru.ravel.ItDesk.service.AuthService;
import ru.ravel.ItDesk.service.MfaService;
import ru.ravel.ItDesk.service.SecurityAuditService;
import ru.ravel.ItDesk.service.UserSessionService;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class WebSecurityConfig {

	private static final String BEARER_PREFIX = "Bearer ";
	private static final String ZABBIX_WEBHOOK_PATH = "/api/v1/integrations/zabbix/**";

	private final AuthService authService;
	private final UserSessionService userSessionService;
	private final SingleSessionFilter singleSessionFilter;
	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final JwtScopeAuthorizationFilter jwtScopeAuthorizationFilter;
	private final LoginRateLimitFilter loginRateLimitFilter;
	private final SecurityAuditService securityAuditService;
	private final MfaService mfaService;
	private final ru.ravel.ItDesk.repository.UserRepository userRepository;
	private final SessionRegistry sessionRegistry;

	public WebSecurityConfig(
			AuthService authService,
			UserSessionService userSessionService,
			SingleSessionFilter singleSessionFilter,
			JwtAuthenticationFilter jwtAuthenticationFilter,
			JwtScopeAuthorizationFilter jwtScopeAuthorizationFilter,
			LoginRateLimitFilter loginRateLimitFilter,
			SecurityAuditService securityAuditService,
			MfaService mfaService,
			ru.ravel.ItDesk.repository.UserRepository userRepository,
			SessionRegistry sessionRegistry
	) {
		this.authService = authService;
		this.userSessionService = userSessionService;
		this.singleSessionFilter = singleSessionFilter;
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.jwtScopeAuthorizationFilter = jwtScopeAuthorizationFilter;
		this.loginRateLimitFilter = loginRateLimitFilter;
		this.securityAuditService = securityAuditService;
		this.mfaService = mfaService;
		this.userRepository = userRepository;
		this.sessionRegistry = sessionRegistry;
	}

	@Bean
	UserDetailsService userDetailsService() {
		return authService;
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

	/**
	 * Dedicated stateless chain for Bearer requests. It only matches /api/** and
	 * therefore a JWT can never authenticate access to HTML/settings/WebSocket routes.
	 * Browser API calls without Authorization: Bearer continue through the session chain below.
	 */
	@Bean
	@Order(1)
	SecurityFilterChain bearerApiSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
				.securityMatcher(WebSecurityConfig::isBearerApiRequest)
				.cors(AbstractHttpConfigurer::disable)
				.csrf(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers("/api/v1/jwt/**").denyAll()
						.requestMatchers("/api/v1/portal/**").denyAll()
						.anyRequest().authenticated())
				.exceptionHandling(exception -> exception
						.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(jwtScopeAuthorizationFilter, JwtAuthenticationFilter.class)
				.build();
	}

	/**
	 * Browser/session chain. CSRF is enabled here because JSESSIONID is sent
	 * automatically by the browser. XSRF-TOKEN is readable by the SPA and Axios
	 * sends it back as X-XSRF-TOKEN for unsafe requests.
	 */
	@Bean
	@Order(2)
	SecurityFilterChain browserSecurityFilterChain(HttpSecurity http) throws Exception {
		HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
		requestCache.setMatchingRequestParameterName(null);

		CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		csrfRepository.setCookiePath("/");

		return http
				.cors(AbstractHttpConfigurer::disable)
				.csrf(csrf -> csrf
						.csrfTokenRepository(csrfRepository)
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
						// Zabbix отправляет события без сессии: доступ по токену интеграции (X-ULDesk-Token)
						.ignoringRequestMatchers(ZABBIX_WEBHOOK_PATH))
				.authorizeHttpRequests(requests -> requests
						.requestMatchers(
								"/swagger-ui.html",
								"/swagger-ui/**",
								"/v3/api-docs",
								"/v3/api-docs/**"
						).hasAnyRole("ADMIN")
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
								"/reset-password",
								"/welcome",
								"/api/v1/support/reset-password",
								"/api/v1/support/reset-password/verify",
								"/api/v1/support/reset-password/confirm",
								ZABBIX_WEBHOOK_PATH,
								"/actuator/health",
								"/actuator/health/**",
								"/actuator/prometheus"
						).permitAll()
						.requestMatchers("/actuator/**").hasRole("ADMIN")
						.requestMatchers("/api/v1/jwt/**").hasRole("ADMIN")
						.requestMatchers("/portal", "/portal/**", "/api/v1/portal/**").hasRole("CLIENT")
						.requestMatchers("/client-portal-ws/**").hasRole("CLIENT")
						.requestMatchers("/chats/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
						.requestMatchers("/tasks/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
						.requestMatchers("/incidents/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
						.requestMatchers("/analytics/**").hasAnyRole("ADMIN", "MANAGER", "SENIOR_OPERATOR", "OPERATOR", "OBSERVER")
						.requestMatchers("/settings", "/settings/profile").authenticated()
						.requestMatchers("/settings/jwt", "/settings/jwt/**").hasRole("ADMIN")
						// Страницы настроек — оболочка SPA; данные защищены правами в API (@access.can), раздел скрывает роутер
						.requestMatchers("/settings/**").authenticated()
						.requestMatchers("/ws/**").authenticated()
						.anyRequest().authenticated())
				.sessionManagement(sessionManagement -> sessionManagement
						.maximumSessions(1)
						.maxSessionsPreventsLogin(false)
						.sessionRegistry(sessionRegistry)
						.expiredUrl("/login?expired"))
				.exceptionHandling(exception -> exception
						.defaultAuthenticationEntryPointFor(
								new IdleTimeoutAuthenticationEntryPoint(),
								PathPatternRequestMatcher.pathPattern("/api/**")
						)
						.defaultAuthenticationEntryPointFor(
								new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
								PathPatternRequestMatcher.pathPattern("/v3/api-docs/**")
						)
						.defaultAuthenticationEntryPointFor(
								(request, response, authException) -> response.sendRedirect(LoginRedirects.withTarget(
										request.getContextPath() + "/login", LoginRedirects.requestTarget(request))),
								PathPatternRequestMatcher.pathPattern("/**")
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
							securityAuditService.loginSucceeded(authentication.getName(), request);
							String redirect = LoginRedirects.safeTarget(request.getParameter("redirect"));
							// Второй фактор: до подтверждения кода MfaEnforcementFilter пускает только на /login/mfa
							var mfaState = userRepository.findByUsername(authentication.getName())
									.flatMap(mfaService::stateAfterPasswordLogin);
							if (mfaState.isPresent()) {
								request.getSession().setAttribute(MfaService.SESSION_STATE, mfaState.get());
								LoginRedirects.rememberForMfa(request.getSession(), redirect);
								response.sendRedirect(LoginRedirects.withTarget(MfaEnforcementFilter.MFA_PAGE, redirect));
								return;
							}
							boolean clientPortalUser = authentication.getAuthorities().stream()
									.anyMatch(authority -> "ROLE_CLIENT".equals(authority.getAuthority()));
							request.getSession().removeAttribute(LoginRedirects.SESSION_TARGET);
							response.sendRedirect(redirect != null ? redirect : clientPortalUser ? "/portal" : "/chats");
						})
						.failureHandler((request, response, exception) -> {
							String reason = exception.getClass().getSimpleName().replace("Exception", "");
							securityAuditService.loginFailed(request.getParameter("username"), reason, request);
							response.sendRedirect(LoginRedirects.withTarget(request.getContextPath() + "/login-error", request.getParameter("redirect")));
						})
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
				.addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterAfter(singleSessionFilter, UsernamePasswordAuthenticationFilter.class)
				.addFilterBefore(new MfaEnforcementFilter(), org.springframework.security.web.access.intercept.AuthorizationFilter.class)
				.build();
	}

	@Bean
	public HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	private static boolean isBearerApiRequest(HttpServletRequest request) {
		String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
		String uri = request.getRequestURI();
		String path = contextPath.isBlank() || !uri.startsWith(contextPath)
				? uri
				: uri.substring(contextPath.length());
		if (!(path.equals("/api") || path.startsWith("/api/"))) {
			return false;
		}
		String authorization = request.getHeader("Authorization");
		return authorization != null
				&& authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
	}
}

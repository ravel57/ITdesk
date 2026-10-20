package ru.ravel.ItDesk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ravel.ItDesk.service.MfaService;

import java.io.IOException;
import java.util.List;


/**
 * Пока после пароля не подтверждён второй фактор, пропускает только страницу ввода кода, её API,
 * статические файлы и выход. Остальные API отвечают 401, страницы перенаправляют на /login/mfa.
 * <p>
 * Не {@code @Component}: иначе Spring Boot зарегистрировал бы фильтр и в общей цепочке сервлета,
 * где он отработал бы раньше Spring Security без контекста сессии.
 */
public class MfaEnforcementFilter extends OncePerRequestFilter {

	public static final String MFA_PAGE = "/login/mfa";

	private static final List<String> ALLOWED_PREFIXES = List.of(
			MFA_PAGE, "/api/v1/auth/mfa/", "/logout", "/csrf", "/login",
			"/js/", "/css/", "/fonts/", "/icons/", "/img/", "/assets/", "/statics/"
	);
	private static final List<String> ALLOWED_EXACT = List.of("/", "/index.html", "/favicon.ico");


	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		HttpSession session = request.getSession(false);
		if (session == null || session.getAttribute(MfaService.SESSION_STATE) == null || isAllowed(path(request))) {
			chain.doFilter(request, response);
			return;
		}
		String path = path(request);
		if (path.startsWith("/api/") || path.startsWith("/ws") || path.startsWith("/files/") || path.startsWith("/client-portal-ws")) {
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"mfaRequired\":true}");
			return;
		}
		String target = LoginRedirects.requestTarget(request);
		LoginRedirects.rememberForMfa(session, target);
		response.sendRedirect(LoginRedirects.withTarget(request.getContextPath() + MFA_PAGE, target));
	}


	private static boolean isAllowed(String path) {
		return ALLOWED_EXACT.contains(path) || ALLOWED_PREFIXES.stream().anyMatch(path::startsWith);
	}


	private static String path(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		return contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : uri;
	}

}

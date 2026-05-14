package ru.ravel.ItDesk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ravel.ItDesk.service.UserSessionService;

import java.io.IOException;


@Component
@RequiredArgsConstructor
public class SingleSessionFilter extends OncePerRequestFilter {

	private final UserSessionService userSessionService;


	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken)) {
			String username = authentication.getName();
			String sessionId = request.getSession(false) == null ? null : request.getSession(false).getId();

			if (sessionId != null && !userSessionService.isCurrentSession(username, sessionId)) {
				request.getSession(false).invalidate();
				SecurityContextHolder.clearContext();
				String uri = request.getRequestURI();
				if (uri.startsWith(request.getContextPath() + "/api/")) {
					response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
					return;
				}
				response.sendRedirect(request.getContextPath() + "/login?sessionExpired=1");
				return;
			}
		}
		filterChain.doFilter(request, response);
	}


	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
			uri = uri.substring(contextPath.length());
		}
		return uri.equals("/")
				|| uri.equals("/index.html")
				|| uri.equals("/favicon.ico")
				|| uri.equals("/login")
				|| uri.equals("/login-error")
				|| uri.equals("/session-expired")
				|| uri.equals("/perform_login")
				|| uri.startsWith("/assets/")
				|| uri.startsWith("/js/")
				|| uri.startsWith("/css/")
				|| uri.startsWith("/icons/")
				|| uri.startsWith("/fonts/")
				|| uri.startsWith("/img/")
				|| uri.startsWith("/statics/")
				|| uri.startsWith("/actuator/");
	}

}
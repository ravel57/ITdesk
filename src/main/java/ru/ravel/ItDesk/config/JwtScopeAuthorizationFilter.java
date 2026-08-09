package ru.ravel.ItDesk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ravel.ItDesk.model.JwtScope;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class JwtScopeAuthorizationFilter extends OncePerRequestFilter {

	private static final Pattern CLIENT_TASK_PATH = Pattern.compile("^/api/v1/client/[^/]+/task(?:/.*)?$");
	private static final Pattern CLIENT_TASK_PINGS_PATH = Pattern.compile("^/api/v1/client/[^/]+/task-pings$");

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtBearerAuthenticationToken jwt)) {
			filterChain.doFilter(request, response);
			return;
		}

		Set<String> scopes = jwt.getScopes();
		if (scopes.contains(JwtScope.API_FULL.getValue())) {
			filterChain.doFilter(request, response);
			return;
		}

		String path = pathWithinApplication(request);
		if ("/api/v1/user/current".equals(path)) {
			filterChain.doFilter(request, response);
			return;
		}

		String requiredScope = requiredScope(request.getMethod(), path, scopes);
		if (requiredScope == null || scopes.contains(requiredScope)) {
			filterChain.doFilter(request, response);
			return;
		}

		response.setStatus(HttpServletResponse.SC_FORBIDDEN);
		response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"" + requiredScope + "\"");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.getWriter().write("{\"error\":\"JWT не имеет необходимого scope: " + requiredScope + "\"}");
	}

	private String requiredScope(String method, String path, Set<String> scopes) {
		if (path.startsWith("/api/v1/analytics/")) {
			return JwtScope.ANALYTICS_READ.getValue();
		}
		if (path.startsWith("/api/v1/webhooks/")) {
			return JwtScope.WEBHOOKS_CALL.getValue();
		}
		if (isTaskReferenceReadPath(method, path)) {
			if (scopes.contains(JwtScope.TASKS_READ.getValue()) || scopes.contains(JwtScope.TASKS_WRITE.getValue())) {
				return null;
			}
			return JwtScope.TASKS_READ.getValue();
		}
		if (isTaskPath(path)) {
			if (isTaskReadOperation(method, path)) {
				if (scopes.contains(JwtScope.TASKS_READ.getValue()) || scopes.contains(JwtScope.TASKS_WRITE.getValue())) {
					return null;
				}
				return JwtScope.TASKS_READ.getValue();
			}
			return JwtScope.TASKS_WRITE.getValue();
		}

		// Granular tokens are deny-by-default. New API areas must be explicitly mapped
		// before they can be used without api:full.
		return JwtScope.API_FULL.getValue();
	}

	private boolean isTaskReferenceReadPath(String method, String path) {
		if (!("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))) {
			return false;
		}
		return path.equals("/api/v1/clients")
				|| path.equals("/api/v1/filters")
				|| path.equals("/api/v1/tags")
				|| path.equals("/api/v1/priorities")
				|| path.equals("/api/v1/statuses")
				|| path.equals("/api/v1/support-lines")
				|| path.startsWith("/api/v1/support-lines/")
				|| path.equals("/api/v1/task-types")
				|| path.startsWith("/api/v1/task-types/")
				|| path.equals("/api/v1/services")
				|| path.matches("^/api/v1/services/[^/]+$");
	}

	private boolean isTaskPath(String path) {
		return "/api/v1/tasks-page".equals(path)
				|| path.startsWith("/api/v1/task/")
				|| CLIENT_TASK_PATH.matcher(path).matches()
				|| CLIENT_TASK_PINGS_PATH.matcher(path).matches();
	}

	private boolean isTaskReadOperation(String method, String path) {
		if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
			return true;
		}
		return "POST".equalsIgnoreCase(method)
				&& ("/api/v1/tasks-page".equals(path) || path.endsWith("/search-messages"));
	}

	private String pathWithinApplication(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String contextPath = request.getContextPath();
		if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
			return uri.substring(contextPath.length());
		}
		return uri;
	}
}

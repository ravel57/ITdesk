package ru.ravel.ItDesk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ravel.ItDesk.service.JwtAuthenticationResult;
import ru.ravel.ItDesk.service.JwtService;
import ru.ravel.ItDesk.service.SecurityAuditService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final JwtService jwtService;
	private final SecurityAuditService securityAuditService;

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain
	) throws ServletException, IOException {
		String authorization = request.getHeader("Authorization");
		if (authorization == null || !authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
			filterChain.doFilter(request, response);
			return;
		}

		String token = authorization.substring(BEARER_PREFIX.length()).trim();
		try {
			JwtAuthenticationResult result = jwtService.authenticate(token);
			JwtBearerAuthenticationToken authentication = new JwtBearerAuthenticationToken(result);
			authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

			SecurityContext context = SecurityContextHolder.createEmptyContext();
			context.setAuthentication(authentication);
			SecurityContextHolder.setContext(context);

			try {
				jwtService.recordUsage(
						result.tokenId(),
						securityAuditService.clientIp(request),
						securityAuditService.userAgent(request),
						securityAuditService.endpoint(request)
				);
			} catch (RuntimeException ignored) {
				// Audit metadata must never make a valid API token unusable.
			}
			securityAuditService.jwtUsed(result, request);

			filterChain.doFilter(request, response);
		} catch (Exception e) {
			SecurityContextHolder.clearContext();
			securityAuditService.jwtRejected(e.getMessage(), request);
			response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
			response.setHeader("WWW-Authenticate", "Bearer");
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.getWriter().write("{\"error\":\"Недействительный, истёкший или отозванный JWT\"}");
		}
	}
}

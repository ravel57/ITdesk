package ru.ravel.ItDesk.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * SPA-compatible CSRF request handler based on the Spring Security reference setup.
 * The raw token is accepted from X-XSRF-TOKEN and from the login form's _csrf field;
 * request attributes still use the XOR handler for BREACH protection when rendered.
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

	private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
	private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			Supplier<CsrfToken> csrfToken
	) {
		this.xor.handle(request, response, csrfToken);
		// Force the deferred token to be written to the XSRF-TOKEN cookie, including
		// after authentication/logout when Spring clears the previous token.
		csrfToken.get();
	}

	@Override
	public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
		String headerValue = request.getHeader(csrfToken.getHeaderName());
		String parameterValue = request.getParameter(csrfToken.getParameterName());
		if (StringUtils.hasText(headerValue) || StringUtils.hasText(parameterValue)) {
			return this.plain.resolveCsrfTokenValue(request, csrfToken);
		}
		return this.xor.resolveCsrfTokenValue(request, csrfToken);
	}
}

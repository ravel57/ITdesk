package ru.ravel.ItDesk.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.ravel.ItDesk.service.LoginRateLimiter;

import java.io.IOException;

@Component
public class LoginRateLimitFilter extends OncePerRequestFilter {

    private final LoginRateLimiter limiter;

    public LoginRateLimitFilter(LoginRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        String uri = request.getRequestURI();
        String path = contextPath.isBlank() || !uri.startsWith(contextPath)
                ? uri
                : uri.substring(contextPath.length());
        return !HttpMethod.POST.matches(request.getMethod()) || !"/perform_login".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String username = request.getParameter("username");
        if (username != null && username.length() > 254) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid username");
            return;
        }
        if (!limiter.allow(request.getRemoteAddr(), username)) {
			response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "900");
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Too many login attempts. Try again later.");
            return;
        }
        chain.doFilter(request, response);
    }
}

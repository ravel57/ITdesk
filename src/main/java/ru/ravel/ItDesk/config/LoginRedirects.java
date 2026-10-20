package ru.ravel.ItDesk.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Local SPA destinations carried through password login and the second factor. */
public final class LoginRedirects {
    public static final String SESSION_TARGET = "ULDESK_LOGIN_TARGET";
    private static final Set<String> AUTH_PATHS = Set.of("/login", "/login-error", "/session-expired",
            "/reset-password", "/logout", "/perform_login");

    private LoginRedirects() {}

    public static String safeTarget(String value) {
        if (value == null || !value.startsWith("/") || value.startsWith("//")
                || value.indexOf('\\') >= 0 || value.chars().anyMatch(c -> c <= 32 || c == 127)) return null;
        try {
            URI uri = URI.create(value);
            String path = uri.getPath();
            if (uri.isAbsolute() || uri.getRawAuthority() != null || path == null || path.startsWith("//")
                    || path.indexOf('\\') >= 0 || path.chars().anyMatch(c -> c < 32 || c == 127)) return null;
            path = new URI(null, null, path, null, null).normalize().getPath().replaceFirst("/$", "");
            if (AUTH_PATHS.contains(path) || path.startsWith("/login/")) return null;
            return value;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String withTarget(String page, String target) {
        String safe = safeTarget(target);
        return safe == null ? page : page + (page.contains("?") ? "&" : "?") + "redirect="
                + URLEncoder.encode(safe, StandardCharsets.UTF_8);
    }

    public static String requestTarget(HttpServletRequest request) {
        String path = request.getRequestURI();
        String context = request.getContextPath();
        if (context != null && !context.isEmpty() && path.startsWith(context)) path = path.substring(context.length());
        return safeTarget(path + (request.getQueryString() == null ? "" : "?" + request.getQueryString()));
    }

    public static void rememberForMfa(HttpSession session, String target) {
        String safe = safeTarget(target);
        if (safe == null) session.removeAttribute(SESSION_TARGET);
        else session.setAttribute(SESSION_TARGET, safe);
    }

    public static String consumeAfterMfa(HttpSession session, String fallback) {
        Object target = session.getAttribute(SESSION_TARGET);
        session.removeAttribute(SESSION_TARGET);
        String safe = target instanceof String text ? safeTarget(text) : null;
        return safe == null ? fallback : safe;
    }
}

package ru.ravel.ItDesk.config;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 401 для /api/**, когда запрос не прошёл аутентификацию не из-за {@link SingleSessionFilter}
 * (тот случай уже помечен своей причиной). Если браузер прислал JSESSIONID, которого сервер уже
 * не знает, это похоже на бездействие дольше таймаута сессии — сообщаем фронту {@code IDLE_TIMEOUT},
 * чтобы страница логина показала понятную причину вместо общей фразы. Без cookie (ещё не входили)
 * тело не пишем — это обычный, ожидаемый 401.
 */
public class IdleTimeoutAuthenticationEntryPoint implements AuthenticationEntryPoint {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
			throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		if (hadSessionCookie(request)) {
			response.setContentType("application/json;charset=UTF-8");
			response.getWriter().write("{\"reason\":\"IDLE_TIMEOUT\"}");
		}
	}


	private static boolean hadSessionCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return false;
		}
		for (Cookie cookie : cookies) {
			if ("JSESSIONID".equals(cookie.getName())) {
				return true;
			}
		}
		return false;
	}

}

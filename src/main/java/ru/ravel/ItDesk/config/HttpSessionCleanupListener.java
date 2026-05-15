package ru.ravel.ItDesk.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.security.web.session.HttpSessionDestroyedEvent;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.service.UserSessionService;

@Component
@RequiredArgsConstructor
public class HttpSessionCleanupListener {

	private final UserSessionService userSessionService;


	@EventListener
	public void onSessionDestroyed(HttpSessionDestroyedEvent event) {
		if (event.getSession() == null) {
			return;
		}
		userSessionService.sessionDestroyed(event.getSession().getId());
	}
}
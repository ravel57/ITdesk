package ru.ravel.ItDesk.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import ru.ravel.ItDesk.service.MfaService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


class MfaEnforcementFilterTest {

	private final MfaEnforcementFilter filter = new MfaEnforcementFilter();


	@Test
	void pendingSecondFactorBlocksApiAndPagesButNotMfaFlow() throws Exception {
		MockHttpSession pending = new MockHttpSession();
		pending.setAttribute(MfaService.SESSION_STATE, MfaService.State.VERIFY);

		assertEquals(401, run("/api/v1/clients", pending).getStatus());
		assertEquals(401, run("/ws/info", pending).getStatus());
		assertEquals(401, run("/files/images/123", pending).getStatus());
		assertEquals("/login/mfa", run("/chats", pending).getRedirectedUrl());

		for (String allowed : new String[]{"/login/mfa", "/api/v1/auth/mfa/verify", "/logout", "/csrf", "/js/app.js", "/"}) {
			assertPassed(allowed, pending);
		}
	}


	@Test
	void completedLoginAndAnonymousRequestsPassThrough() throws Exception {
		assertPassed("/api/v1/clients", new MockHttpSession());
		assertPassed("/api/v1/clients", null);
	}


	private MockHttpServletResponse run(String path, MockHttpSession session) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		if (session != null) {
			request.setSession(session);
		}
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);
		filter.doFilter(request, response, chain);
		return response;
	}


	private void assertPassed(String path, MockHttpSession session) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
		if (session != null) {
			request.setSession(session);
		}
		FilterChain chain = mock(FilterChain.class);
		filter.doFilter(request, new MockHttpServletResponse(), chain);
		verify(chain).doFilter(any(), any());
	}

}

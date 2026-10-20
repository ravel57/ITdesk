package ru.ravel.ItDesk.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRedirectsTest {
    @Test
    void preservesTheCompleteTargetThroughLoginFailureAndMfa() {
        String target = "/tasks/81?tab=history&search=%D1%82%D0%B5%D1%81%D1%82#comment-7";
        for (String page : new String[]{"/login", "/login-error", "/login/mfa", "/login?sessionExpired=1"}) {
            String location = LoginRedirects.withTarget(page, target);
            assertThat(URLDecoder.decode(location.substring(location.indexOf("redirect=") + 9), StandardCharsets.UTF_8)).isEqualTo(target);
        }
        MockHttpSession session = new MockHttpSession();
        LoginRedirects.rememberForMfa(session, target);
        assertThat(LoginRedirects.consumeAfterMfa(session, "/chats")).isEqualTo(target);
        assertThat(LoginRedirects.consumeAfterMfa(session, "/chats")).isEqualTo("/chats");
    }

    @Test
    void rejectsExternalDestinationsAndAuthenticationLoops() {
        for (String target : new String[]{"https://other.test", "//other.test", "/\\other.test", "/%2fother.test",
                "javascript:alert(1)", "/tasks\n", "/%0d%0aLocation:test", "/login", "/login/?redirect=/tasks", "/login/mfa",
                "/session-expired", "/login-error", "/foo/../login", "/reset-password?token=secret", "/logout", "/perform_login"}) {
            assertThat(LoginRedirects.safeTarget(target)).as(target).isNull();
        }
        assertThat(LoginRedirects.safeTarget("/knowledge/Some%20article")).isEqualTo("/knowledge/Some%20article");
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(LoginRedirects.SESSION_TARGET, "https://other.test");
        assertThat(LoginRedirects.consumeAfterMfa(session, "/portal")).isEqualTo("/portal");
    }

    @Test
    void buildsTheTargetOfDirectServerRequestsIncludingTheirQuery() {
        var request = new MockHttpServletRequest("GET", "/app/tasks/81");
        request.setContextPath("/app");
        request.setQueryString("tab=history&sort=desc");
        assertThat(LoginRedirects.requestTarget(request)).isEqualTo("/tasks/81?tab=history&sort=desc");
    }
}

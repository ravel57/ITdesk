package ru.ravel.ItDesk.config;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import ru.ravel.ItDesk.controller.MfaController;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.repository.UserRepository;
import ru.ravel.ItDesk.service.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BrowserLoginRedirectTest {
    @Test
    void directLinksPasswordFailuresAndSecondFactorPreserveTheDestination() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WebSecurityConfig.class, Dependencies.class);
            context.refresh();
            var users = context.getBean(UserRepository.class);
            var mfa = context.getBean(MfaService.class);
            var user = User.builder().username("operator").authorities(List.of(Role.OPERATOR)).build();
            when(users.findByUsername("operator")).thenReturn(Optional.of(user));
            when(mfa.stateAfterPasswordLogin(user)).thenReturn(Optional.empty());
            var mvc = MockMvcBuilders.standaloneSetup(new MfaController(mfa, users))
                    .apply(springSecurity(context.getBean(FilterChainProxy.class))).build();
            String target = "/tasks/81?tab=history&sort=desc#comment-7";
            mvc.perform(get("/tasks/81?tab=history&sort=desc"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(LoginRedirects.withTarget("/login", "/tasks/81?tab=history&sort=desc")));
            mvc.perform(get("/api/v1/user/current")).andExpect(status().isUnauthorized());
            mvc.perform(post("/perform_login").cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-XSRF-TOKEN", "test-token").param("username", "operator").param("password", "wrong").param("redirect", target))
                    .andExpect(redirectedUrl(LoginRedirects.withTarget("/login-error", target)));
            mvc.perform(post("/perform_login").cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-XSRF-TOKEN", "test-token").param("username", "operator").param("password", "correct").param("redirect", target))
                    .andExpect(redirectedUrl(target));
            mvc.perform(post("/perform_login").cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-XSRF-TOKEN", "test-token").param("username", "operator").param("password", "correct").param("redirect", "//other.test"))
                    .andExpect(redirectedUrl("/chats"));

            when(mfa.stateAfterPasswordLogin(user)).thenReturn(Optional.of(MfaService.State.VERIFY));
            var login = mvc.perform(post("/perform_login").cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-XSRF-TOKEN", "test-token").param("username", "operator").param("password", "correct").param("redirect", target))
                    .andExpect(redirectedUrl(LoginRedirects.withTarget("/login/mfa", target))).andReturn();
            var session = (MockHttpSession) login.getRequest().getSession();
            assertThat(session.getAttribute(LoginRedirects.SESSION_TARGET)).isEqualTo(target);
            when(mfa.verify(user, "123456")).thenReturn(true);
            mvc.perform(post("/api/v1/auth/mfa/verify").session(session).cookie(new Cookie("XSRF-TOKEN", "test-token")).header("X-XSRF-TOKEN", "test-token")
                            .contentType("application/json").content("{\"code\":\"123456\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.redirect").value(target));
            assertThat(session.getAttribute(LoginRedirects.SESSION_TARGET)).isNull();
        }
    }

    @Configuration
    static class Dependencies {
        @Bean AuthService authService() {
            var auth = mock(AuthService.class);
            when(auth.loadUserByUsername(anyString())).thenAnswer(call -> org.springframework.security.core.userdetails.User
                    .withUsername("operator").password("hash").roles("OPERATOR").build());
            return auth;
        }
        @Bean PasswordEncoder passwordEncoder() {
            var encoder = mock(PasswordEncoder.class);
            when(encoder.matches(any(), anyString())).thenAnswer(call -> "correct".contentEquals((CharSequence) call.getArgument(0)));
            return encoder;
        }
        @Bean UserSessionService sessions() { return mock(UserSessionService.class); }
        @Bean SecurityAuditService audit() { return mock(SecurityAuditService.class); }
        @Bean MfaService mfa() { return mock(MfaService.class); }
        @Bean UserRepository users() { return mock(UserRepository.class); }
        @Bean SessionRegistry registry() { return new SessionRegistryImpl(); }
        @Bean SingleSessionFilter singleSession() throws Exception { return pass(SingleSessionFilter.class); }
        @Bean JwtAuthenticationFilter jwt() throws Exception { return pass(JwtAuthenticationFilter.class); }
        @Bean JwtScopeAuthorizationFilter scope() throws Exception { return pass(JwtScopeAuthorizationFilter.class); }
        @Bean LoginRateLimitFilter rateLimit() throws Exception { return pass(LoginRateLimitFilter.class); }
        private static <T extends Filter> T pass(Class<T> type) throws Exception {
            T filter = mock(type);
            doAnswer(call -> { ((FilterChain) call.getArgument(2)).doFilter(call.getArgument(0), call.getArgument(1)); return null; })
                    .when(filter).doFilter(any(), any(), any());
            return filter;
        }
    }
}

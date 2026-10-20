package ru.ravel.ItDesk.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MvcConfigTest {
    @Configuration
    @EnableWebMvc
    @Import(MvcConfig.class)
    static class TestConfig {
    }

    @ParameterizedTest
    @ValueSource(strings = {"/welcome", "/reset-password", "/login/mfa"})
    void directPageLinksLoadSpa(String path) throws Exception {
        try (var context = context()) {
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get(path).queryParam("token", "test-invitation-key"))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void unknownApiRoutesAreNotForwardedToSpa() throws Exception {
        try (var context = context()) {
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(get("/api/v1/unknown-route"))
                    .andExpect(status().isNotFound());
        }
    }

    private AnnotationConfigWebApplicationContext context() {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(TestConfig.class);
        context.refresh();
        return context;
    }
}

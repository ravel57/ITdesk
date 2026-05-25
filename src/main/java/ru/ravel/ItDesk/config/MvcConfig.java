package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class MvcConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(@NotNull ViewControllerRegistry registry) {
		String spa = "forward:/index.html";
		registry.addViewController("/").setViewName(spa);
		registry.addViewController("/login").setViewName(spa);
		registry.addViewController("/login-error").setViewName(spa);
		registry.addViewController("/session-expired").setViewName(spa);
		registry.addViewController("/chats").setViewName(spa);
		registry.addViewController("/chats/**").setViewName(spa);
		registry.addViewController("/tasks").setViewName(spa);
		registry.addViewController("/tasks/**").setViewName(spa);
		registry.addViewController("/settings").setViewName(spa);
		registry.addViewController("/settings/**").setViewName(spa);
		registry.addViewController("/history").setViewName(spa);
		registry.addViewController("/search").setViewName(spa);
		registry.addViewController("/users").setViewName(spa);
		registry.addViewController("/analytics").setViewName(spa);
		registry.addViewController("/phone").setViewName(spa);
		registry.addViewController("/help").setViewName(spa);
		registry.addViewController("/my-tasks").setViewName(spa);
		registry.addViewController("/orgs").setViewName(spa);
		registry.addViewController("/knowledge-base").setViewName(spa);
	}

}
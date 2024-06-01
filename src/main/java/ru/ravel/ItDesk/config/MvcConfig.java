package ru.ravel.ItDesk.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.ViewResolver;
import org.springframework.web.servlet.config.annotation.*;
import org.springframework.web.servlet.view.InternalResourceViewResolver;
import org.springframework.web.servlet.view.JstlView;

@Configuration
class MvcConfig implements WebMvcConfigurer {

	@Override
	public void addViewControllers(@NotNull ViewControllerRegistry registry) {
//		registry.addViewController("/login").setViewName("index");
		registry.addViewController("/chats").setViewName("index");
		registry.addViewController("/chats/{id}").setViewName("index");
		registry.addViewController("/tasks").setViewName("index");
		registry.addViewController("/history").setViewName("index");
		registry.addViewController("/search").setViewName("index");
		registry.addViewController("/settings").setViewName("index");
		registry.addViewController("/settings/**").setViewName("index");
		registry.addViewController("/users").setViewName("index");
		registry.addViewController("/analytics").setViewName("index");
		registry.addViewController("/phone").setViewName("index");
	}

}
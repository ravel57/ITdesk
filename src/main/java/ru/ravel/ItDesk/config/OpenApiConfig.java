package ru.ravel.ItDesk.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;


@Configuration
@SecurityScheme(
		name = "bearerAuth",
		type = SecuritySchemeType.HTTP,
		scheme = "bearer",
		bearerFormat = "JWT",
		description = "JWT администратора из Настройки -> JWT API"
)
@OpenAPIDefinition(
		info = @Info(
				title = "ULDesk API",
				version = "v1",
				description = "Внутренняя REST API документация ULDesk. Swagger UI доступен только после авторизации в приложении."
		)
)
public class OpenApiConfig {
}
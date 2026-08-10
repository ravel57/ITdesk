package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ravel.ItDesk.dto.WebhookCallResult;
import ru.ravel.ItDesk.dto.WebhookTestRequest;
import ru.ravel.ItDesk.service.WebhookDeliveryException;
import ru.ravel.ItDesk.service.WebhookService;

import java.util.Map;


@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
@Tag(name = "Webhooks", description = "Проверка исходящих webhook-вызовов")
public class WebhookController {

	private final WebhookService webhookService;

	@PostMapping("/test")
	@Operation(
			summary = "Проверить webhook",
			description = "Отправляет POST с JSON-телом на указанный URL. Доступно только ADMIN и MANAGER."
	)
	public ResponseEntity<?> test(@RequestBody WebhookTestRequest request) {
		if (request == null) {
			return ResponseEntity.badRequest().body(Map.of("error", "Тело запроса не указано"));
		}
		try {
			WebhookCallResult result = webhookService.call(request.url(), request.body(), request.headers());
			return ResponseEntity.ok(result);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
		} catch (WebhookDeliveryException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", safeMessage(e)));
		}
	}

	private String safeMessage(Exception e) {
		return e.getMessage() == null || e.getMessage().isBlank() ? "Ошибка webhook" : e.getMessage();
	}
}
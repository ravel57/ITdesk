package ru.ravel.ItDesk.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.service.ZabbixWebhookService;

import java.nio.charset.StandardCharsets;
import java.util.Map;


/**
 * Приём событий Zabbix (режим веб-хука). Без сессии и CSRF: доступ по токену интеграции в заголовке X-ULDesk-Token.
 * Bearer-заголовок не используется: его перехватывает проверка JWT.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/integrations/zabbix/{key}")
@RequiredArgsConstructor
@Tag(name = "Zabbix: веб-хук", description = "Приём событий Zabbix по контракту uldesk.zabbix.v1")
public class ZabbixWebhookController {

	private static final int MAX_BODY_BYTES = 64 * 1024;

	private final ZabbixWebhookService webhookService;
	private final ObjectMapper objectMapper;


	@Operation(summary = "Принять событие Zabbix", description = "Контракт uldesk.zabbix.v1, токен — в заголовке X-ULDesk-Token")
	@PostMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<Map<String, Object>> receiveEvent(
			@PathVariable String key,
			@RequestHeader(value = ZabbixWebhookService.TOKEN_HEADER, required = false) String token,
			@RequestBody(required = false) byte[] body,
			HttpServletRequest request
	) {
		if (body != null && body.length > MAX_BODY_BYTES) {
			return ResponseEntity.status(413).body(Map.of("accepted", false, "message", "Тело запроса больше 64 КБ"));
		}
		JsonNode json;
		try {
			json = body == null || body.length == 0 ? null : objectMapper.readTree(new String(body, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(Map.of("accepted", false, "message", "Тело запроса — не JSON"));
		}
		try {
			ZabbixWebhookService.Reply reply = webhookService.receiveEvent(key, token, request.getRemoteAddr(), json);
			return ResponseEntity.status(reply.status()).body(reply.body());
		} catch (Exception e) {
			log.error("Zabbix webhook failed for key={}", key, e);
			// 5xx: Zabbix повторит отправку по настройке «Попытки»
			return ResponseEntity.status(500).body(Map.of("accepted", false, "message", "Ошибка ULDesk, событие будет отправлено повторно"));
		}
	}


	@Operation(summary = "Проверка связи с Zabbix", description = "Элемент данных «HTTP agent» в Zabbix. Ответ 1 — связь есть")
	@RequestMapping(value = "/heartbeat", method = {RequestMethod.GET, RequestMethod.POST})
	public ResponseEntity<String> heartbeat(
			@PathVariable String key,
			@RequestHeader(value = ZabbixWebhookService.TOKEN_HEADER, required = false) String token,
			HttpServletRequest request
	) {
		ZabbixWebhookService.Reply reply = webhookService.heartbeat(key, token, request.getRemoteAddr());
		// Числовой ответ, чтобы элемент данных в Zabbix мог быть числовым
		return reply.status() == 200
				? ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body("1")
				: ResponseEntity.status(reply.status()).contentType(MediaType.TEXT_PLAIN)
				.body(String.valueOf(reply.body().get("message")));
	}
}

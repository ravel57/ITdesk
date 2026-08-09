package ru.ravel.ItDesk.dto;

import java.util.Map;

public record WebhookTestRequest(
		String url,
		Object body,
		Map<String, String> headers
) {
}

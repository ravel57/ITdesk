package ru.ravel.ItDesk.dto;

import java.util.List;
import java.util.Map;

public record WebhookCallResult(
		int statusCode,
		String responseBody,
		Map<String, List<String>> responseHeaders,
		long durationMs
) {
	public boolean successful() {
		return statusCode >= 200 && statusCode < 300;
	}
}

package ru.ravel.ItDesk.model;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public enum JwtScope {

	API_FULL("api:full", "Полный доступ к API", "Доступ ко всем API-методам, разрешённым администратору"),
	TASKS_READ("tasks:read", "Заявки: чтение", "Просмотр заявок, истории, SLA и поиск сообщений по заявкам"),
	TASKS_WRITE("tasks:write", "Заявки: изменение", "Создание и изменение заявок, сообщений и SLA"),
	ANALYTICS_READ("analytics:read", "Аналитика: чтение", "Просмотр аналитики и управление собственным запросом аналитики"),
	WEBHOOKS_CALL("webhooks:call", "Webhooks: вызов", "Тестовые и исходящие webhook-вызовы через API");

	private final String value;
	private final String title;
	private final String description;

	JwtScope(String value, String title, String description) {
		this.value = value;
		this.title = title;
		this.description = description;
	}

	public String getValue() {
		return value;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public static List<String> normalize(List<String> requested) {
		if (requested == null || requested.isEmpty()) {
			return List.of(API_FULL.value);
		}
		Set<String> supported = new LinkedHashSet<>();
		for (String value : requested) {
			String normalized = value == null ? "" : value.trim();
			if (normalized.isBlank()) {
				continue;
			}
			if (Arrays.stream(values()).noneMatch(scope -> scope.value.equals(normalized))) {
				throw new IllegalArgumentException("Неизвестный JWT scope: " + normalized);
			}
			supported.add(normalized);
		}
		if (supported.isEmpty()) {
			throw new IllegalArgumentException("Выберите хотя бы один JWT scope");
		}
		if (supported.contains(API_FULL.value)) {
			return List.of(API_FULL.value);
		}
		return List.copyOf(supported);
	}

	public static JwtScope byValue(String value) {
		return Arrays.stream(values())
				.filter(scope -> scope.value.equals(value))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Неизвестный JWT scope: " + value));
	}
}

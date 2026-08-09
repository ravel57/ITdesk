package ru.ravel.ItDesk.plugins;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Ограничение частоты вызова хуков плагинов из интерфейса.
 * <p>
 * Каждый вызов хука порождает отдельный процесс JVM, поэтому пользователь,
 * открывающий карточку в цикле, способен исчерпать ресурсы сервиса без всякого
 * злого умысла. Счётчик локальный: при нескольких инстансах его стоит
 * дополнить ограничением на ingress, как и в случае LoginRateLimiter.
 */
@Component
public class PluginHookRateLimiter {

	private static final long WINDOW_MS = Duration.ofMinutes(1).toMillis();
	private static final int MAX_KEYS = 20_000;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	private final int maxPerMinute;


	public PluginHookRateLimiter(
			@Value("${uldesk.plugins.hooks.max-per-minute:30}")
			int configuredMaxPerMinute
	) {
		this.maxPerMinute = Math.max(1, Math.min(600, configuredMaxPerMinute));
	}


	public boolean allow(Long userId, String hookName) {
		long now = System.currentTimeMillis();

		if (windows.size() > MAX_KEYS) {
			windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt() >= WINDOW_MS);
			if (windows.size() > MAX_KEYS) {
				return false;
			}
		}

		String key = "user:" + userId + ":hook:" + normalize(hookName);
		final boolean[] allowed = {false};

		windows.compute(key, (ignored, previous) -> {
			Window current = previous;
			if (current == null || now - current.startedAt() >= WINDOW_MS) {
				current = new Window(now, 0);
			}
			if (current.count() >= maxPerMinute) {
				return current;
			}
			allowed[0] = true;
			return new Window(current.startedAt(), current.count() + 1);
		});

		return allowed[0];
	}


	private String normalize(String value) {
		if (value == null) {
			return "";
		}
		String normalized = value.trim().toLowerCase(Locale.ROOT);
		return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
	}


	private record Window(long startedAt, int count) {
	}
}

package ru.ravel.ItDesk.plugins;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;


/**
 * Разобранное право плагина.
 * <p>
 * Грамматика:
 * <ul>
 *     <li>{@code http:<method>:<url-prefix>} — исходящий HTTP(S)-доступ к
 *     конкретному origin/path-prefix;</li>
 *     <li>{@code secret:<name>} — использовать секрет, значение которого
 *     хранится у конкретной установки и никогда не находится в коде плагина;</li>
 *     <li>{@code url-scheme:<scheme>} — открыть пользовательскую URI-схему
 *     из declarative frontend после явного одобрения администратором;</li>
 *     <li>{@code <resource>:read} — получить данные текущей сущности этого
 *     типа в sandbox context, например {@code client:read}. Ресурс не
 *     перечисляется в ядре: это стабильная capability-грамматика, а наличие
 *     конкретной сущности определяет вызывающий код платформы.</li>
 * </ul>
 * Манифест только запрашивает права. Действуют лишь строки, которые отдельно
 * одобрены администратором установки.
 */
public record PluginPermission(
		Kind kind,
		String method,
		URI prefix,
		String secretName,
		String urlScheme,
		String resourceName,
		String canonical
) {

	public enum Kind {
		HTTP,
		SECRET,
		URL_SCHEME,
		RESOURCE_READ
	}

	public static final Set<String> ALLOWED_METHODS =
			Set.of("get", "post", "put", "patch", "delete", "head");

	private static final String SECRET_NAME_PATTERN = "[a-z0-9][a-z0-9._-]{0,63}";
	private static final String URL_SCHEME_PATTERN = "[a-z][a-z0-9+.-]{0,31}";
	private static final String RESOURCE_NAME_PATTERN = "[a-z][a-z0-9._-]{0,63}";
	private static final Set<String> FORBIDDEN_URL_SCHEMES = Set.of(
			"javascript",
			"data",
			"file",
			"vbscript",
			"blob"
	);
	private static final int MAX_LENGTH = 1024;


	public static Optional<PluginPermission> tryParse(String raw) {
		try {
			return Optional.of(parse(raw));
		} catch (RuntimeException e) {
			return Optional.empty();
		}
	}


	public static PluginPermission parse(String raw) {
		if (raw == null || raw.isBlank()) {
			throw new IllegalArgumentException("Право плагина не заполнено");
		}

		String normalized = raw.trim();
		if (normalized.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("Право плагина длиннее " + MAX_LENGTH + " символов");
		}

		if (normalized.startsWith("secret:")) {
			return parseSecret(normalized);
		}
		if (normalized.startsWith("http:")) {
			return parseHttp(normalized);
		}
		if (normalized.startsWith("url-scheme:")) {
			return parseUrlScheme(normalized);
		}
		if (normalized.toLowerCase(Locale.ROOT).endsWith(":read")) {
			return parseResourceRead(normalized);
		}

		throw new IllegalArgumentException(
				"Неизвестное право плагина: " + normalized
						+ ". Поддерживаются http:<метод>:<префикс URL>, secret:<имя>, "
						+ "url-scheme:<схема> и <resource>:read"
		);
	}


	private static PluginPermission parseSecret(String normalized) {
		String name = normalized.substring("secret:".length()).trim().toLowerCase(Locale.ROOT);
		if (!name.matches(SECRET_NAME_PATTERN)) {
			throw new IllegalArgumentException("Некорректное имя секрета в праве: " + normalized);
		}
		return new PluginPermission(
				Kind.SECRET,
				null,
				null,
				name,
				null,
				null,
				"secret:" + name
		);
	}


	private static PluginPermission parseUrlScheme(String normalized) {
		String scheme = normalized.substring("url-scheme:".length()).trim().toLowerCase(Locale.ROOT);
		if (!scheme.matches(URL_SCHEME_PATTERN)) {
			throw new IllegalArgumentException("Некорректная URL-схема в праве: " + normalized);
		}
		if (FORBIDDEN_URL_SCHEMES.contains(scheme)) {
			throw new IllegalArgumentException("URL-схема запрещена для плагинов: " + scheme);
		}
		return new PluginPermission(
				Kind.URL_SCHEME,
				null,
				null,
				null,
				scheme,
				null,
				"url-scheme:" + scheme
		);
	}


	private static PluginPermission parseResourceRead(String normalized) {
		int separator = normalized.lastIndexOf(':');
		String action = separator < 0 ? "" : normalized.substring(separator + 1).trim().toLowerCase(Locale.ROOT);
		String resource = separator < 0 ? "" : normalized.substring(0, separator).trim().toLowerCase(Locale.ROOT);

		if (!"read".equals(action) || !resource.matches(RESOURCE_NAME_PATTERN)) {
			throw new IllegalArgumentException("Некорректное право чтения ресурса: " + normalized);
		}
		if (Set.of("http", "secret", "url-scheme").contains(resource)) {
			throw new IllegalArgumentException("Зарезервированное имя ресурса: " + resource);
		}

		return new PluginPermission(
				Kind.RESOURCE_READ,
				null,
				null,
				null,
				null,
				resource,
				resource + ":read"
		);
	}


	private static PluginPermission parseHttp(String normalized) {
		String rest = normalized.substring("http:".length());
		int separator = rest.indexOf(':');
		if (separator <= 0) {
			throw new IllegalArgumentException(
					"Ожидался формат http:<метод>:<префикс URL>, получено: " + normalized
			);
		}

		String method = rest.substring(0, separator).trim().toLowerCase(Locale.ROOT);
		if (!ALLOWED_METHODS.contains(method)) {
			throw new IllegalArgumentException(
					"Метод " + method + " не поддерживается. Допустимы: " + ALLOWED_METHODS
			);
		}

		URI prefix = parsePrefix(rest.substring(separator + 1).trim());
		return new PluginPermission(
				Kind.HTTP,
				method,
				prefix,
				null,
				null,
				null,
				"http:" + method + ":" + prefix
		);
	}


	/**
	 * Схема здесь не ограничивается https: on-prem инсталляции иногда ходят
	 * во внутренние сервисы без TLS. Решение о plaintext/private targets
	 * остаётся в PluginEgressPolicy и не зашивается в плагины поставщика.
	 */
	private static URI parsePrefix(String value) {
		URI uri;
		try {
			uri = URI.create(value).normalize();
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Некорректный URL в праве плагина: " + value, e);
		}

		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("https") && !scheme.equals("http")) {
			throw new IllegalArgumentException("URL в праве плагина должен начинаться с http:// или https://");
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			throw new IllegalArgumentException("URL в праве плагина должен содержать host: " + value);
		}
		if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("URL в праве плагина не может содержать user-info, query или fragment");
		}
		if (uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException("Некорректный порт в праве плагина: " + value);
		}

		String path = uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath();
		if (!path.endsWith("/")) {
			throw new IllegalArgumentException(
					"Префикс пути в праве плагина должен заканчиваться на «/»: " + value
			);
		}

		try {
			return new URI(scheme, null, uri.getHost().toLowerCase(Locale.ROOT), uri.getPort(), path, null, null);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("Некорректный URL в праве плагина: " + value, e);
		}
	}
}

package ru.ravel.ItDesk.plugins;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.ravel.ItDesk.service.OutboundUrlGuard;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;


/**
 * Потолок, который задаёт оператор инсталляции, а не разработчик плагина.
 * <p>
 * Схема доступа трёхслойная:
 * <ol>
 *     <li>плагин объявляет нужные адреса в манифесте;</li>
 *     <li>администратор инсталляции одобряет конкретные права
 *     ({@link PluginPermissionService});</li>
 *     <li>эта политика ограничивает, что администратор вообще вправе одобрить.</li>
 * </ol>
 * По умолчанию потолок пустой: разрешён любой публичный https-адрес, одобренный
 * администратором. Жёсткого списка доменов вендора здесь нет и быть не должно —
 * инсталляции интегрируются со своими системами.
 */
@Component
public class PluginEgressPolicy {

	private final OutboundUrlGuard outboundUrlGuard;

	/** Необязательное сужение: если непусто, плагины ходят только внутрь этих префиксов. */
	private final Set<URI> operatorPrefixes;

	private final Set<String> deniedHosts;

	private final boolean allowPrivateTargets;

	private final boolean allowPlaintext;


	public PluginEgressPolicy(
			OutboundUrlGuard outboundUrlGuard,

			@Value("${uldesk.plugins.http.allowed-prefixes:}")
			String configuredPrefixes,

			@Value("${uldesk.plugins.http.denied-hosts:}")
			String configuredDeniedHosts,

			@Value("${uldesk.plugins.http.allow-private-targets:false}")
			boolean allowPrivateTargets,

			@Value("${uldesk.plugins.http.allow-plaintext:false}")
			boolean allowPlaintext
	) {
		this.outboundUrlGuard = outboundUrlGuard;
		this.operatorPrefixes = parsePrefixes(configuredPrefixes);
		this.deniedHosts = parseHosts(configuredDeniedHosts);
		this.allowPrivateTargets = allowPrivateTargets;
		this.allowPlaintext = allowPlaintext;
	}


	/**
	 * Проверка права в момент одобрения администратором: без DNS-резолва,
	 * чтобы недоступный в эту секунду хост не мешал настроить плагин.
	 */
	public void validatePrefix(URI prefix) {
		if (prefix == null) {
			throw new IllegalArgumentException("Префикс URL не заполнен");
		}
		if (!allowPlaintext && !"https".equalsIgnoreCase(prefix.getScheme())) {
			throw new IllegalArgumentException(
					"Разрешены только https-адреса."
							+ " Для внутренних сервисов без TLS включите uldesk.plugins.http.allow-plaintext"
			);
		}
		if (isDeniedHost(prefix.getHost())) {
			throw new IllegalArgumentException("Хост запрещён политикой инсталляции: " + prefix.getHost());
		}
		if (!withinOperatorCeiling(prefix)) {
			throw new IllegalArgumentException(
					"Адрес вне списка uldesk.plugins.http.allowed-prefixes: " + prefix
			);
		}
	}


	/**
	 * Полная проверка конкретного запроса плагина, включая резолв адресов.
	 * Приватные и служебные диапазоны блокируются, пока оператор явно не
	 * разрешит внутренние цели: on-prem инсталляции ходят в свои же системы.
	 */
	public URI validateRequestTarget(String url) {
		URI uri = outboundUrlGuard.validate(url, allowPrivateTargets);
		if (!allowPlaintext && !"https".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("Разрешены только https-адреса");
		}
		if (isDeniedHost(uri.getHost())) {
			throw new IllegalArgumentException("Хост запрещён политикой инсталляции");
		}
		if (!withinOperatorCeiling(uri)) {
			throw new IllegalArgumentException("Адрес вне разрешённых префиксов инсталляции");
		}
		return uri;
	}


	/** Повторная проверка после подстановки секретов в URL. */
	public void revalidateResolvedAddresses(URI uri) {
		if (allowPrivateTargets) {
			return;
		}
		outboundUrlGuard.validateResolvedAddresses(uri);
	}


	public boolean matchesPrefix(URI prefix, URI request) {
		if (prefix == null || request == null) {
			return false;
		}
		if (!outboundUrlGuard.sameOrigin(prefix, request)) {
			return false;
		}
		return normalizedPath(request).startsWith(normalizedPath(prefix));
	}


	private boolean withinOperatorCeiling(URI uri) {
		if (operatorPrefixes.isEmpty()) {
			return true;
		}
		for (URI prefix : operatorPrefixes) {
			if (matchesPrefix(prefix, uri)) {
				return true;
			}
		}
		return false;
	}


	private boolean isDeniedHost(String host) {
		if (host == null || host.isBlank()) {
			return true;
		}
		String normalized = host.trim().toLowerCase(Locale.ROOT);
		for (String denied : deniedHosts) {
			if (denied.startsWith("*.")) {
				String suffix = denied.substring(1);
				if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
					return true;
				}
			} else if (denied.equals(normalized)) {
				return true;
			}
		}
		return false;
	}


	private String normalizedPath(URI uri) {
		String path = uri.normalize().getPath();
		if (path == null || path.isBlank()) {
			return "/";
		}
		return path;
	}


	private Set<URI> parsePrefixes(String configured) {
		if (configured == null || configured.isBlank()) {
			return Set.of();
		}
		Set<URI> result = new LinkedHashSet<>();
		Arrays.stream(configured.split(","))
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.map(value -> PluginPermission.parse("http:get:" + value).prefix())
				.forEach(result::add);
		return Set.copyOf(result);
	}


	private Set<String> parseHosts(String configured) {
		if (configured == null || configured.isBlank()) {
			return Set.of();
		}
		Set<String> result = new LinkedHashSet<>();
		Arrays.stream(configured.split(","))
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.map(value -> value.toLowerCase(Locale.ROOT))
				.forEach(result::add);
		return Set.copyOf(result);
	}
}

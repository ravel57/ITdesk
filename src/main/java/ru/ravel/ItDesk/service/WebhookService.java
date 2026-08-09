package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.WebhookCallResult;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class WebhookService {

	private static final int MAX_RESPONSE_BODY_LENGTH = 32_768;
	private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
	private static final Set<String> REDIRECT_SENSITIVE_HEADERS = Set.of(
			"authorization", "proxy-authorization", "cookie"
	);

	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;
	private final Duration requestTimeout;
	private final int maxRedirects;
	private final Set<String> privateHostAllowlist;

	public WebhookService(
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper,
			@Value("${app.webhooks.connect-timeout-ms:3000}") long connectTimeoutMs,
			@Value("${app.webhooks.request-timeout-ms:10000}") long requestTimeoutMs,
			@Value("${app.webhooks.max-redirects:3}") int maxRedirects,
			@Value("${app.webhooks.private-host-allowlist:}") String privateHostAllowlist
	) {
		this.objectMapper = objectMapper;
		this.requestTimeout = Duration.ofMillis(Math.max(1, requestTimeoutMs));
		this.maxRedirects = Math.max(0, Math.min(maxRedirects, 10));
		this.privateHostAllowlist = parseAllowlist(privateHostAllowlist);
		this.httpClient = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs)))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	public WebhookCallResult call(String url, Object body) {
		return call(url, body, Map.of());
	}

	public WebhookCallResult call(String url, Object body, Map<String, String> headers) {
		URI initialUri = validateAndResolveTarget(url);
		String payload = serialize(body);
		Map<String, String> normalizedHeaders = safeHeaders(headers);

		URI currentUri = initialUri;
		long startedAt = System.nanoTime();
		for (int redirectCount = 0; ; redirectCount++) {
			validateResolvedAddresses(currentUri);
			HttpRequest request = buildRequest(
					currentUri,
					payload,
					filterRedirectHeaders(normalizedHeaders, initialUri, currentUri)
			);
			try {
				HttpResponse<String> response = httpClient.send(
						request,
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
				);

				if (REDIRECT_STATUSES.contains(response.statusCode()) && response.headers().firstValue("Location").isPresent()) {
					if (redirectCount >= maxRedirects) {
						throw new WebhookDeliveryException("Webhook превысил допустимое число перенаправлений");
					}
					URI nextUri = validateAndResolveTarget(currentUri.resolve(response.headers().firstValue("Location").orElseThrow()).toString());
					if ("https".equalsIgnoreCase(currentUri.getScheme()) && "http".equalsIgnoreCase(nextUri.getScheme())) {
						throw new WebhookDeliveryException("Webhook redirect с HTTPS на HTTP запрещён");
					}
					currentUri = nextUri;
					continue;
				}

				return new WebhookCallResult(
						response.statusCode(),
						truncate(response.body()),
						response.headers().map(),
						elapsedMs(startedAt)
				);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new WebhookDeliveryException("Вызов webhook был прерван", e);
			} catch (WebhookDeliveryException e) {
				throw e;
			} catch (Exception e) {
				throw new WebhookDeliveryException("Не удалось вызвать webhook: " + e.getMessage(), e);
			}
		}
	}

	private HttpRequest buildRequest(URI uri, String payload, Map<String, String> headers) {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
				.timeout(requestTimeout)
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Accept", "application/json")
				.header("User-Agent", "ULDesk-Webhook/1.0")
				.POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

		headers.forEach((name, value) -> requestBuilder.header(name, value));
		return requestBuilder.build();
	}

	private URI validateAndResolveTarget(String url) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("URL webhook не указан");
		}

		URI uri;
		try {
			uri = URI.create(url.trim()).normalize();
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Некорректный URL webhook", e);
		}

		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("Webhook поддерживает только http/https URL");
		}
		if (uri.getUserInfo() != null) {
			throw new IllegalArgumentException("Логин и пароль в URL webhook не поддерживаются");
		}
		if (uri.getFragment() != null) {
			throw new IllegalArgumentException("Fragment (#...) в URL webhook не поддерживается");
		}
		if (uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException("Некорректный порт webhook");
		}

		String host = normalizeHost(uri.getHost());
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("URL webhook должен содержать host");
		}
		if ((host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local"))
				&& !isPrivateHostAllowed(host)) {
			throw new IllegalArgumentException("Локальные адреса webhook запрещены");
		}

		validateResolvedAddresses(uri);
		return uri;
	}

	private void validateResolvedAddresses(URI uri) {
		String host = normalizeHost(uri.getHost());
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("URL webhook должен содержать host");
		}
		if (isPrivateHostAllowed(host)) {
			return;
		}

		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException e) {
			throw new IllegalArgumentException("Не удалось разрешить host webhook", e);
		}
		if (addresses.length == 0) {
			throw new IllegalArgumentException("Host webhook не разрешился ни в один IP-адрес");
		}
		for (InetAddress address : addresses) {
			if (isBlockedAddress(address)) {
				throw new IllegalArgumentException("Webhook на локальный, приватный или служебный IP-адрес запрещён");
			}
		}
	}

	private boolean isBlockedAddress(InetAddress address) {
		if (address.isAnyLocalAddress()
				|| address.isLoopbackAddress()
				|| address.isLinkLocalAddress()
				|| address.isSiteLocalAddress()
				|| address.isMulticastAddress()) {
			return true;
		}

		byte[] raw = address.getAddress();
		if (address instanceof Inet4Address && raw.length == 4) {
			int a = Byte.toUnsignedInt(raw[0]);
			int b = Byte.toUnsignedInt(raw[1]);
			int c = Byte.toUnsignedInt(raw[2]);
			return a == 0
					|| (a == 100 && b >= 64 && b <= 127)       // carrier-grade NAT
					|| (a == 192 && b == 0 && c == 0)          // IETF protocol assignments
					|| (a == 192 && b == 0 && c == 2)          // documentation
					|| (a == 198 && (b == 18 || b == 19))      // benchmark network
					|| (a == 198 && b == 51 && c == 100)       // documentation
					|| (a == 203 && b == 0 && c == 113)        // documentation
					|| a >= 240;                                // reserved/broadcast
		}
		if (address instanceof Inet6Address && raw.length == 16) {
			int first = Byte.toUnsignedInt(raw[0]);
			return (first & 0xFE) == 0xFC; // fc00::/7 unique-local addresses
		}
		return false;
	}

	private String normalizeHost(String host) {
		if (host == null) {
			return null;
		}
		String normalized = host.trim().toLowerCase(Locale.ROOT);
		while (normalized.endsWith(".")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		try {
			return IDN.toASCII(normalized);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Некорректный host webhook", e);
		}
	}

	private Set<String> parseAllowlist(String configured) {
		if (configured == null || configured.isBlank()) {
			return Set.of();
		}
		Set<String> result = new LinkedHashSet<>();
		Arrays.stream(configured.split(","))
				.map(String::trim)
				.filter(value -> !value.isBlank())
				.map(value -> value.startsWith("*.") ? "*." + normalizeHost(value.substring(2)) : normalizeHost(value))
				.forEach(result::add);
		return Set.copyOf(result);
	}

	private boolean isPrivateHostAllowed(String host) {
		for (String allowed : privateHostAllowlist) {
			if (allowed.startsWith("*.")) {
				String suffix = allowed.substring(1); // includes the leading dot
				if (host.endsWith(suffix) && host.length() > suffix.length()) {
					return true;
				}
			} else if (allowed.equals(host)) {
				return true;
			}
		}
		return false;
	}

	private Map<String, String> filterRedirectHeaders(Map<String, String> headers, URI initialUri, URI currentUri) {
		if (sameOrigin(initialUri, currentUri)) {
			return headers;
		}
		Map<String, String> result = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			if (!REDIRECT_SENSITIVE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				result.put(name, value);
			}
		});
		return result;
	}

	private boolean sameOrigin(URI a, URI b) {
		return a.getScheme().equalsIgnoreCase(b.getScheme())
				&& normalizeHost(a.getHost()).equals(normalizeHost(b.getHost()))
				&& effectivePort(a) == effectivePort(b);
	}

	private int effectivePort(URI uri) {
		if (uri.getPort() > 0) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

	private String serialize(Object body) {
		try {
			return objectMapper.writeValueAsString(body == null ? Map.of() : body);
		} catch (Exception e) {
			throw new IllegalArgumentException("Не удалось сериализовать тело webhook в JSON", e);
		}
	}

	private Map<String, String> safeHeaders(Map<String, String> headers) {
		if (headers == null || headers.isEmpty()) {
			return Map.of();
		}
		Map<String, String> result = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			if (name == null || value == null) {
				return;
			}
			String normalized = name.trim();
			if (normalized.isBlank()
					|| normalized.equalsIgnoreCase("Content-Length")
					|| normalized.equalsIgnoreCase("Host")
					|| normalized.equalsIgnoreCase("Connection")) {
				return;
			}
			result.put(normalized, value);
		});
		return result;
	}

	private long elapsedMs(long startedAt) {
		return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
	}

	private String truncate(String value) {
		if (value == null || value.length() <= MAX_RESPONSE_BODY_LENGTH) {
			return value;
		}
		return value.substring(0, MAX_RESPONSE_BODY_LENGTH) + "…";
	}
}

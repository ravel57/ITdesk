package ru.ravel.ItDesk.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Central SSRF guard for workflow-controlled outbound HTTP calls.
 * Application-level DNS checks are defense-in-depth; production should also
 * enforce egress filtering at the container/VPC boundary to close DNS rebinding TOCTOU.
 */
@Component
public class OutboundUrlGuard {

	private final Set<String> privateHostAllowlist;

	public OutboundUrlGuard(
			@Value("${app.automation.http.private-host-allowlist:}") String privateHostAllowlist
	) {
		this.privateHostAllowlist = parseAllowlist(privateHostAllowlist);
	}

	public URI validate(String url) {
		return validate(url, false);
	}

	/**
	 * allowPrivateTargets = true отключает проверку приватных и служебных
	 * адресов. Нужен on-prem сценариям, где интеграция идёт во внутреннюю
	 * систему заказчика; включается только явным решением оператора.
	 */
	public URI validate(String url, boolean allowPrivateTargets) {
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("URL не заполнен");
		}
		final URI uri;
		try {
			uri = URI.create(url.trim()).normalize();
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Некорректный URL", exception);
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new IllegalArgumentException("Разрешены только http/https URL");
		}
		if (uri.getUserInfo() != null || uri.getFragment() != null) {
			throw new IllegalArgumentException("User-info и fragment в URL запрещены");
		}
		if (uri.getPort() == 0 || uri.getPort() > 65535) {
			throw new IllegalArgumentException("Некорректный порт");
		}
		String host = normalizeHost(uri.getHost());
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("URL должен содержать host");
		}
		if (!allowPrivateTargets) {
			if ((host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local"))
					&& !isPrivateHostAllowed(host)) {
				throw new IllegalArgumentException("Локальные адреса запрещены");
			}
			validateResolvedAddresses(uri);
		}
		return uri;
	}

	public void validateResolvedAddresses(URI uri) {
		String host = normalizeHost(uri.getHost());
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("URL должен содержать host");
		}
		if (isPrivateHostAllowed(host)) {
			return;
		}
		final InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(host);
		} catch (UnknownHostException exception) {
			throw new IllegalArgumentException("Не удалось разрешить host", exception);
		}
		if (addresses.length == 0) {
			throw new IllegalArgumentException("Host не разрешился ни в один IP-адрес");
		}
		for (InetAddress address : addresses) {
			if (isBlockedAddress(address)) {
				throw new IllegalArgumentException("Локальный, приватный или служебный IP-адрес запрещён");
			}
		}
	}

	public boolean sameOrigin(URI a, URI b) {
		return a != null && b != null
				&& a.getScheme().equalsIgnoreCase(b.getScheme())
				&& normalizeHost(a.getHost()).equals(normalizeHost(b.getHost()))
				&& effectivePort(a) == effectivePort(b);
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
					|| (a == 100 && b >= 64 && b <= 127)
					|| (a == 192 && b == 0 && (c == 0 || c == 2))
					|| (a == 198 && (b == 18 || b == 19))
					|| (a == 198 && b == 51 && c == 100)
					|| (a == 203 && b == 0 && c == 113)
					|| a >= 240;
		}
		if (address instanceof Inet6Address && raw.length == 16) {
			int first = Byte.toUnsignedInt(raw[0]);
			return (first & 0xFE) == 0xFC;
		}
		return false;
	}

	private String normalizeHost(String host) {
		if (host == null) return null;
		String normalized = host.trim().toLowerCase(Locale.ROOT);
		while (normalized.endsWith(".")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		try {
			return IDN.toASCII(normalized);
		} catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Некорректный host", exception);
		}
	}

	private Set<String> parseAllowlist(String configured) {
		if (configured == null || configured.isBlank()) return Set.of();
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
				String suffix = allowed.substring(1);
				if (host.endsWith(suffix) && host.length() > suffix.length()) return true;
			} else if (allowed.equals(host)) {
				return true;
			}
		}
		return false;
	}

	private int effectivePort(URI uri) {
		if (uri.getPort() > 0) return uri.getPort();
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}
}

package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


@Component
public class PluginSandboxBroker {

	public static final String SANDBOX_SOCKET_PATH =
			"/run/uldesk/plugin-broker.sock";

	private static final int MAX_BROKER_REQUEST_BYTES = 512 * 1024;
	private static final long MAX_GRANT_TTL_MS = 60_000L;

	/** Заголовки ответа, которые видны плагину: остальные скрыты. */
	private static final Set<String> EXPOSED_RESPONSE_HEADERS = Set.of(
			"content-type",
			"retry-after"
	);

	private static final Set<String> ALLOWED_REQUEST_CONTENT_TYPES = Set.of(
			"application/json",
			"application/x-www-form-urlencoded",
			"text/plain",
			"text/xml",
			"application/xml"
	);

	private static final AtomicInteger THREAD_ID = new AtomicInteger();

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final SecureRandom secureRandom = new SecureRandom();

	private final PluginEgressPolicy egressPolicy;
	private final PluginSecretStore secretStore;

	private final HttpClient httpClient;

	private final int requestTimeoutMs;

	private final int maxRequestsPerInvocation;

	private final int maxResponseBytes;

	private final int maxRequestBodyBytes;

	private final Map<String, Grant> grants = new ConcurrentHashMap<>();

	private final ExecutorService acceptExecutor =
			Executors.newSingleThreadExecutor(
					daemonThreadFactory("plugin-broker-accept")
			);

	private final ThreadPoolExecutor workerExecutor =
			new ThreadPoolExecutor(
					2,
					8,
					30L,
					TimeUnit.SECONDS,
					new ArrayBlockingQueue<>(32),
					daemonThreadFactory("plugin-broker-worker"),
					new ThreadPoolExecutor.AbortPolicy()
			);

	private volatile ServerSocketChannel serverSocketChannel;
	private volatile Path socketPath;
	private final Path configuredSocketPath;


	public PluginSandboxBroker(
			PluginEgressPolicy egressPolicy,
			PluginSecretStore secretStore,

			@Value("${uldesk.plugins.http.connect-timeout-ms:3000}")
			int configuredConnectTimeoutMs,

			@Value("${uldesk.plugins.http.request-timeout-ms:5000}")
			int configuredRequestTimeoutMs,

			@Value("${uldesk.plugins.http.max-requests-per-invocation:16}")
			int configuredMaxRequests,

			@Value("${uldesk.plugins.http.max-response-bytes:262144}")
			int configuredMaxResponseBytes,

			@Value("${uldesk.plugins.http.max-request-body-bytes:65536}")
			int configuredMaxRequestBodyBytes,

			@Value("${uldesk.plugins.broker.socket:/run/uldesk-ipc/plugin-broker.sock}")
			String configuredSocketPath
	) {
		this.egressPolicy = egressPolicy;
		this.secretStore = secretStore;

		int connectTimeoutMs =
				Math.max(
						500,
						Math.min(10_000, configuredConnectTimeoutMs)
				);

		this.requestTimeoutMs =
				Math.max(
						500,
						Math.min(15_000, configuredRequestTimeoutMs)
				);

		this.maxRequestsPerInvocation =
				Math.max(1, Math.min(64, configuredMaxRequests));

		this.maxResponseBytes =
				Math.max(4096, Math.min(4 * 1024 * 1024, configuredMaxResponseBytes));

		this.maxRequestBodyBytes =
				Math.max(0, Math.min(1024 * 1024, configuredMaxRequestBodyBytes));

		this.configuredSocketPath = Path.of(configuredSocketPath).toAbsolutePath().normalize();

		this.httpClient = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(Duration.ofMillis(connectTimeoutMs))
				.build();
	}


	public String issueGrant(
			String pluginKey,
			Collection<String> permissions,
			Collection<PluginSecretBinding> secretBindings,
			long pluginTimeoutMs
	) {
		ensureStarted();
		cleanupExpiredGrants();

		/*
		 * Сюда приходят уже одобренные администратором права:
		 * фильтрация по заявке плагина выполняется в GroovyPluginRuntime.
		 */
		List<PluginPermission> parsedPermissions = new ArrayList<>();

		if (permissions != null) {
			for (String permission : permissions) {
				PluginPermission.tryParse(permission).ifPresent(parsedPermissions::add);
			}
		}

		String token = randomToken();

		long ttlMs =
				Math.max(
						5_000L,
						Math.min(
								MAX_GRANT_TTL_MS,
								pluginTimeoutMs + 5_000L
						)
				);

		grants.put(
				token,
				new Grant(
						Objects.toString(pluginKey, ""),
						List.copyOf(parsedPermissions),
						secretBindings == null ? List.of() : List.copyOf(secretBindings),
						System.currentTimeMillis() + ttlMs,
						new AtomicInteger()
				)
		);

		return token;
	}


	public void revokeGrant(String token) {
		if (token != null) {
			grants.remove(token);
		}
	}


	public Path socketPath() {
		ensureStarted();

		Path path = socketPath;

		if (path == null) {
			throw new IllegalStateException(
					"Plugin sandbox broker socket is unavailable"
			);
		}

		return path;
	}


	private synchronized void ensureStarted() {
		if (serverSocketChannel != null && serverSocketChannel.isOpen()) {
			return;
		}

		try {
			Path path = configuredSocketPath;
			Path directory = path.getParent();
			if (directory == null
					|| !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)
					|| Files.isSymbolicLink(directory)) {
				throw new IllegalStateException("Plugin IPC directory is unavailable or unsafe: " + directory);
			}

			try {
				Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(directory);
				if (permissions.contains(PosixFilePermission.OTHERS_READ)
						|| permissions.contains(PosixFilePermission.OTHERS_WRITE)
						|| permissions.contains(PosixFilePermission.OTHERS_EXECUTE)) {
					throw new IllegalStateException("Plugin IPC directory must not be accessible to others: " + directory);
				}
			} catch (UnsupportedOperationException e) {
				throw new IllegalStateException("POSIX permissions are required for plugin IPC", e);
			}

			if (Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
				int owner = ((Number) Files.getAttribute(path, "unix:uid", java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue();
				if (owner != currentUid()) {
					throw new IllegalStateException("Refusing to remove broker socket owned by another uid: " + path);
				}
				Files.delete(path);
			}

			ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
			channel.bind(UnixDomainSocketAddress.of(path));
			Files.setPosixFilePermissions(path, EnumSet.of(
					PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
					PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE
			));

			socketPath = path;
			serverSocketChannel = channel;
			acceptExecutor.submit(this::acceptLoop);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to start plugin sandbox broker", e);
		}
	}

	private static int currentUid() throws Exception {
		for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
			if (line.startsWith("Uid:")) {
				return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
			}
		}
		throw new IllegalStateException("Cannot determine process uid");
	}


	private void acceptLoop() {
		while (true) {
			ServerSocketChannel server = serverSocketChannel;

			if (server == null || !server.isOpen()) {
				return;
			}

			try {
				SocketChannel channel = server.accept();

				try {
					workerExecutor.execute(
							() -> handleConnection(channel)
					);
				} catch (RejectedExecutionException rejected) {
					try {
						channel.close();
					} catch (Exception ignored) {
					}
				}

			} catch (Exception e) {
				if (server.isOpen()) {
					logger.warn(
							"Plugin broker accept failed",
							e
					);
				}

				return;
			}
		}
	}


	private void handleConnection(SocketChannel channel) {
		try (
				SocketChannel ignoredChannel = channel;
				InputStream input = Channels.newInputStream(channel);
				OutputStream output = Channels.newOutputStream(channel)
		) {
			Map<String, Object> response;

			try {
				String json =
						readBoundedLine(
								input,
								MAX_BROKER_REQUEST_BYTES
						);

				@SuppressWarnings("unchecked")
				Map<String, Object> request =
						objectMapper.readValue(
								json,
								Map.class
						);

				response = executeRequest(request);

			} catch (BrokerDenied denied) {
				response = Map.of(
						"ok", false,
						"error", denied.code
				);

			} catch (Exception e) {
				logger.debug(
						"Plugin broker request failed: {}",
						e.getClass().getSimpleName()
				);

				response = Map.of(
						"ok", false,
						"error", "BROKER_ERROR"
				);
			}

			byte[] bytes =
					objectMapper.writeValueAsBytes(response);

			output.write(bytes);
			output.write('\n');
			output.flush();

		} catch (Exception ignored) {
		}
	}


	private Map<String, Object> executeRequest(
			Map<String, Object> request
	) throws Exception {

		String token =
				Objects.toString(
						request.get("token"),
						""
				);

		Grant grant = grants.get(token);

		if (grant == null) {
			throw denied("INVALID_GRANT");
		}

		if (grant.expiresAt < System.currentTimeMillis()) {
			grants.remove(token);
			throw denied("EXPIRED_GRANT");
		}

		if (grant.requests.incrementAndGet()
				> maxRequestsPerInvocation) {
			throw denied("REQUEST_LIMIT");
		}

		String method =
				Objects.toString(
								request.get("method"),
								""
						)
						.trim()
						.toUpperCase(Locale.ROOT);

		if (!PluginPermission.ALLOWED_METHODS.contains(
				method.toLowerCase(Locale.ROOT)
		)) {
			throw denied("METHOD_NOT_ALLOWED");
		}

		String url =
				Objects.toString(
						request.get("url"),
						""
				);

		final URI uri;

		try {
			uri = egressPolicy.validateRequestTarget(url);
		} catch (RuntimeException e) {
			throw denied("TARGET_NOT_ALLOWED");
		}

		if (!grantAllows(grant, method, uri)) {
			throw denied("PERMISSION_DENIED");
		}

		Map<String, String> secretQueryParameters =
				new LinkedHashMap<>();

		Map<String, String> secretHeaders =
				new LinkedHashMap<>();

		/*
		 * Секреты подставляются автоматически по объявлениям манифеста:
		 * код плагина их не упоминает и, соответственно, не может увести
		 * токен на посторонний адрес.
		 */
		applyDeclaredSecrets(
				grant,
				uri,
				secretQueryParameters,
				secretHeaders
		);

		/* Совместимость со старым API: явно перечисленные query-секреты. */
		secretQueryParameters.putAll(
				resolveQuerySecrets(
						grant,
						uri,
						request.get("querySecrets"),
						secretQueryParameters.keySet()
				)
		);

		URI effectiveUri =
				appendQuerySecrets(
						uri,
						secretQueryParameters
				);

		/*
		 * Повторная SSRF-проверка после добавления secret query.
		 * Сам secret URL в лог не выводится.
		 */
		try {
			effectiveUri =
					egressPolicy.validateRequestTarget(
							effectiveUri.toString()
					);

			egressPolicy.revalidateResolvedAddresses(
					effectiveUri
			);
		} catch (RuntimeException e) {
			throw denied("TARGET_NOT_ALLOWED");
		}

		String body =
				Objects.toString(
						request.get("body"),
						""
				);

		if (!body.isEmpty()) {
			if ("GET".equals(method) || "HEAD".equals(method)) {
				throw denied("BODY_NOT_ALLOWED");
			}
			if (body.getBytes(StandardCharsets.UTF_8).length
					> maxRequestBodyBytes) {
				throw denied("REQUEST_BODY_TOO_LARGE");
			}
		}

		String contentType =
				Objects.toString(
								request.get("contentType"),
								"application/json"
						)
						.trim()
						.toLowerCase(Locale.ROOT);

		if (!body.isEmpty()
				&& !ALLOWED_REQUEST_CONTENT_TYPES.contains(contentType)) {
			throw denied("CONTENT_TYPE_NOT_ALLOWED");
		}

		HttpRequest.Builder upstreamBuilder =
				HttpRequest.newBuilder(effectiveUri)
						.timeout(
								Duration.ofMillis(
										requestTimeoutMs
								)
						)
						.header(
								"Accept",
								"text/plain, application/json;q=0.9, */*;q=0.1"
						)
						.header(
								"User-Agent",
								"ULDesk-Plugin-Broker/1"
						)
						.method(
								method,
								body.isEmpty()
										? HttpRequest.BodyPublishers.noBody()
										: HttpRequest.BodyPublishers.ofString(
										body,
										StandardCharsets.UTF_8
								)
						);

		if (!body.isEmpty()) {
			upstreamBuilder.header("Content-Type", contentType);
		}

		for (Map.Entry<String, String> header
				: secretHeaders.entrySet()) {

			upstreamBuilder.header(
					header.getKey(),
					header.getValue()
			);
		}

		HttpRequest upstreamRequest = upstreamBuilder.build();

		HttpResponse<InputStream> upstreamResponse =
				httpClient.send(
						upstreamRequest,
						HttpResponse.BodyHandlers.ofInputStream()
				);

		byte[] bodyBytes;

		try (InputStream responseBody = upstreamResponse.body()) {
			bodyBytes =
					responseBody.readNBytes(
							maxResponseBytes + 1
					);
		}

		if (bodyBytes.length > maxResponseBytes) {
			throw denied("RESPONSE_TOO_LARGE");
		}

		Map<String, String> responseHeaders =
				new LinkedHashMap<>();

		upstreamResponse.headers().map().forEach((name, values) -> {
			if (EXPOSED_RESPONSE_HEADERS.contains(
					name.toLowerCase(Locale.ROOT)
			) && !values.isEmpty()) {
				responseHeaders.put(
						name.toLowerCase(Locale.ROOT),
						values.getFirst()
				);
			}
		});

		return Map.of(
				"ok", true,
				"status", upstreamResponse.statusCode(),
				"headers", responseHeaders,
				"body", new String(
						bodyBytes,
						StandardCharsets.UTF_8
				)
		);
	}


	/**
	 * Подставляет секреты, объявленные в манифесте, если администратор одобрил
	 * право secret:<имя> и адрес запроса попадает в объявленный список sendTo.
	 */
	private void applyDeclaredSecrets(
			Grant grant,
			URI uri,
			Map<String, String> queryParameters,
			Map<String, String> headers
	) {
		for (PluginSecretBinding binding : grant.secretBindings) {

			if (!grantHasSecret(grant, binding.name())) {
				continue;
			}

			boolean targetMatches = false;

			for (URI target : binding.sendTo()) {
				if (egressPolicy.matchesPrefix(target, uri)) {
					targetMatches = true;
					break;
				}
			}

			if (!targetMatches) {
				continue;
			}

			final String secret;

			try {
				secret = secretStore.resolve(
						grant.pluginKey(),
						binding.name()
				);
			} catch (IllegalStateException e) {
				throw denied("SECRET_NOT_CONFIGURED");
			}

			String value = binding.valuePrefix() + secret;

			if (binding.header()) {
				if (value.contains("\r") || value.contains("\n")) {
					throw denied("INVALID_SECRET_VALUE");
				}
				headers.put(binding.parameter(), value);
				continue;
			}

			if (containsQueryParameter(uri, binding.parameter())) {
				throw denied("DUPLICATE_SECRET_QUERY_PARAMETER");
			}

			queryParameters.put(binding.parameter(), value);
		}
	}


	private Map<String, String> resolveQuerySecrets(
			Grant grant,
			URI uri,
			Object rawSecrets,
			Set<String> alreadyApplied
	) {
		if (rawSecrets == null) {
			return Map.of();
		}

		if (!(rawSecrets instanceof Map<?, ?> map)) {
			throw denied("INVALID_SECRET_REQUEST");
		}

		if (map.size() > 8) {
			throw denied("TOO_MANY_SECRETS");
		}

		Map<String, String> result =
				new LinkedHashMap<>();

		for (Map.Entry<?, ?> entry : map.entrySet()) {

			String queryName =
					Objects.toString(
							entry.getKey(),
							""
					).trim();

			String secretName =
					Objects.toString(
									entry.getValue(),
									""
							)
							.trim()
							.toLowerCase(Locale.ROOT);

			if (!queryName.matches(
					"[A-Za-z0-9._~-]{1,64}"
			)) {
				throw denied("INVALID_QUERY_NAME");
			}

			if (!secretName.matches(
					"[a-z0-9][a-z0-9._-]{0,63}"
			)) {
				throw denied("INVALID_SECRET_NAME");
			}

			if (!grantHasSecret(grant, secretName)) {
				throw denied("SECRET_PERMISSION_DENIED");
			}

			if (!legacyQuerySecretIsDeclaredForTarget(grant, secretName, queryName, uri)) {
				throw denied("SECRET_BINDING_DENIED");
			}

			if (alreadyApplied.contains(queryName)) {
				continue;
			}

			/*
			 * Legacy querySecrets не должен обходить secrets.sendTo. Даже старый
			 * вызов допустим только если manifest объявляет ровно этот query binding
			 * и текущий URL входит в его sendTo.
			 */
			if (!declaredQuerySecretAllows(grant, uri, queryName, secretName)) {
				throw denied("SECRET_TARGET_NOT_ALLOWED");
			}

			if (containsQueryParameter(
					uri,
					queryName
			)) {
				throw denied(
						"DUPLICATE_SECRET_QUERY_PARAMETER"
				);
			}

			final String secret;

			try {
				secret = secretStore.resolve(
						grant.pluginKey(),
						secretName
				);
			} catch (IllegalStateException e) {
				throw denied("SECRET_NOT_CONFIGURED");
			}

			result.put(queryName, secret);
		}

		return result;
	}


	private boolean declaredQuerySecretAllows(
			Grant grant,
			URI uri,
			String queryName,
			String secretName
	) {
		for (PluginSecretBinding binding : grant.secretBindings) {
			if (binding.header()
					|| !binding.name().equals(secretName)
					|| !binding.parameter().equals(queryName)) {
				continue;
			}
			for (URI target : binding.sendTo()) {
				if (egressPolicy.matchesPrefix(target, uri)) {
					return true;
				}
			}
		}
		return false;
	}



	private boolean legacyQuerySecretIsDeclaredForTarget(
			Grant grant,
			String secretName,
			String queryName,
			URI uri
	) {
		for (PluginSecretBinding binding : grant.secretBindings) {
			if (binding.header()
					|| !binding.name().equals(secretName)
					|| !binding.parameter().equals(queryName)) {
				continue;
			}
			for (URI target : binding.sendTo()) {
				if (egressPolicy.matchesPrefix(target, uri)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean grantAllows(
			Grant grant,
			String method,
			URI requestUri
	) {
		for (PluginPermission permission : grant.permissions) {

			if (permission.kind() != PluginPermission.Kind.HTTP) {
				continue;
			}

			if (!permission.method().equalsIgnoreCase(method)) {
				continue;
			}

			if (egressPolicy.matchesPrefix(
					permission.prefix(),
					requestUri
			)) {
				return true;
			}
		}

		return false;
	}


	private boolean grantHasSecret(
			Grant grant,
			String secretName
	) {
		for (PluginPermission permission : grant.permissions) {

			if (permission.kind() == PluginPermission.Kind.SECRET
					&& permission.secretName().equals(secretName)) {
				return true;
			}
		}

		return false;
	}


	private URI appendQuerySecrets(
			URI uri,
			Map<String, String> secrets
	) {
		if (secrets.isEmpty()) {
			return uri;
		}

		StringBuilder extraQuery =
				new StringBuilder();

		for (Map.Entry<String, String> entry
				: secrets.entrySet()) {

			if (!extraQuery.isEmpty()) {
				extraQuery.append('&');
			}

			extraQuery
					.append(urlEncode(entry.getKey()))
					.append('=')
					.append(urlEncode(entry.getValue()));
		}

		String separator =
				uri.getRawQuery() == null
						? "?"
						: "&";

		return URI.create(
				uri.toASCIIString()
						+ separator
						+ extraQuery
		);
	}


	private boolean containsQueryParameter(
			URI uri,
			String expectedName
	) {
		String query = uri.getRawQuery();

		if (query == null || query.isBlank()) {
			return false;
		}

		for (String item : query.split("&")) {

			int equals = item.indexOf('=');

			String rawName =
					equals >= 0
							? item.substring(0, equals)
							: item;

			String decoded;

			try {
				decoded =
						URLDecoder.decode(
								rawName,
								StandardCharsets.UTF_8
						);
			} catch (IllegalArgumentException e) {
				throw denied("INVALID_QUERY");
			}

			if (expectedName.equals(decoded)) {
				return true;
			}
		}

		return false;
	}


	private static String urlEncode(String value) {
		return URLEncoder.encode(
				value,
				StandardCharsets.UTF_8
		).replace("+", "%20");
	}


	private String randomToken() {
		byte[] token = new byte[32];

		secureRandom.nextBytes(token);

		return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(token);
	}


	private void cleanupExpiredGrants() {
		long now = System.currentTimeMillis();

		grants.entrySet().removeIf(
				entry -> entry.getValue().expiresAt < now
		);
	}


	private String readBoundedLine(
			InputStream input,
			int limit
	) throws Exception {

		ByteArrayOutputStream output =
				new ByteArrayOutputStream();

		while (true) {
			int value = input.read();

			if (value < 0 || value == '\n') {
				break;
			}

			if (value == '\r') {
				continue;
			}

			if (output.size() >= limit) {
				throw denied("REQUEST_TOO_LARGE");
			}

			output.write(value);
		}

		return output.toString(
				StandardCharsets.UTF_8
		);
	}


	private static BrokerDenied denied(
			String code
	) {
		return new BrokerDenied(code);
	}


	private static ThreadFactory daemonThreadFactory(
			String prefix
	) {
		return runnable -> {
			Thread thread =
					new Thread(
							runnable,
							prefix + "-" + THREAD_ID.incrementAndGet()
					);

			thread.setDaemon(true);

			return thread;
		};
	}


	@PreDestroy
	public synchronized void close() {
		grants.clear();

		ServerSocketChannel server =
				serverSocketChannel;

		serverSocketChannel = null;

		if (server != null) {
			try {
				server.close();
			} catch (Exception ignored) {
			}
		}

		acceptExecutor.shutdownNow();
		workerExecutor.shutdownNow();

		if (socketPath != null) {
			try {
				Files.deleteIfExists(socketPath);
			} catch (Exception ignored) {
			}
		}

	}


	private record Grant(
			String pluginKey,
			List<PluginPermission> permissions,
			List<PluginSecretBinding> secretBindings,
			long expiresAt,
			AtomicInteger requests
	) {
	}


	private static class BrokerDenied
			extends RuntimeException {

		private final String code;


		private BrokerDenied(String code) {
			super(code);
			this.code = code;
		}
	}
}
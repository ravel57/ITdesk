package ru.ravel.ItDesk.plugins;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


public final class PluginSandboxApi {

	private static final int MAX_BROKER_RESPONSE_BYTES =
			512 * 1024;

	private static final ObjectMapper OBJECT_MAPPER =
			new ObjectMapper();

	private final Path socketPath;
	private final String token;


	public PluginSandboxApi(
			Path socketPath,
			String token
	) {
		this.socketPath = socketPath;
		this.token = token;
	}


	public Map<String, Object> httpGet(
			String url
	) {
		return httpGet(
				url,
				Map.of()
		);
	}


	/**
	 * Универсальный вызов: метод, тело и тип содержимого.
	 * <p>
	 * Секреты здесь не упоминаются: брокер сам подставит те, что объявлены
	 * в манифесте плагина и одобрены администратором, и только на адреса
	 * из sendTo. Пример вызова из плагина:
	 * <pre>
	 * ctx.sandbox.http([
	 *     method     : 'POST',
	 *     url        : 'https://api.example.com/v1/tickets/',
	 *     contentType: 'application/json',
	 *     body       : groovy.json.JsonOutput.toJson(payload)
	 * ])
	 * </pre>
	 */
	public Map<String, Object> http(
			Map<?, ?> call
	) {
		if (call == null) {
			throw new IllegalArgumentException(
					"Request is empty"
			);
		}

		Map<String, Object> request =
				new LinkedHashMap<>();

		request.put("token", token);

		request.put(
				"method",
				Objects.toString(
						call.get("method"),
						"GET"
				)
		);

		request.put(
				"url",
				Objects.toString(
						call.get("url"),
						""
				)
		);

		if (call.get("body") != null) {
			request.put(
					"body",
					Objects.toString(
							call.get("body"),
							""
					)
			);
		}

		if (call.get("contentType") != null) {
			request.put(
					"contentType",
					Objects.toString(
							call.get("contentType"),
							""
					)
			);
		}

		return requireOk(
				callBroker(request)
		);
	}


	public Map<String, Object> httpGet(
			String url,
			Map<?, ?> querySecrets
	) {
		Map<String, String> normalizedSecrets =
				new LinkedHashMap<>();

		if (querySecrets != null) {
			if (querySecrets.size() > 8) {
				throw new IllegalArgumentException(
						"Too many secret query parameters"
				);
			}

			querySecrets.forEach(
					(key, value) -> {
						String queryName =
								Objects.toString(
										key,
										""
								).trim();

						String secretName =
								Objects.toString(
										value,
										""
								).trim();

						normalizedSecrets.put(
								queryName,
								secretName
						);
					}
			);
		}

		Map<String, Object> request =
				new LinkedHashMap<>();

		request.put("token", token);
		request.put("method", "GET");
		request.put("url", url);
		request.put(
				"querySecrets",
				normalizedSecrets
		);

		return requireOk(
				callBroker(request)
		);
	}


	private Map<String, Object> requireOk(
			Map<String, Object> response
	) {
		if (!Boolean.TRUE.equals(
				response.get("ok")
		)) {
			throw new IllegalStateException(
					"Sandbox HTTP request denied: "
							+ Objects.toString(
							response.get("error"),
							"BROKER_ERROR"
					)
			);
		}

		return response;
	}


	private Map<String, Object> callBroker(
			Map<String, Object> request
	) {
		if (socketPath == null
				|| token == null
				|| token.isBlank()) {
			throw new IllegalStateException(
					"Plugin broker capability is unavailable"
			);
		}

		try (
				SocketChannel channel =
						SocketChannel.open(
								StandardProtocolFamily.UNIX
						)
		) {
			channel.connect(
					UnixDomainSocketAddress.of(
							socketPath
					)
			);

			try (
					InputStream input =
							Channels.newInputStream(channel);

					OutputStream output =
							Channels.newOutputStream(channel)
			) {
				output.write(
						OBJECT_MAPPER.writeValueAsBytes(
								request
						)
				);

				output.write('\n');
				output.flush();

				String responseJson =
						readBoundedLine(input);

				@SuppressWarnings("unchecked")
				Map<String, Object> result =
						OBJECT_MAPPER.readValue(
								responseJson,
								Map.class
						);

				return result;
			}

		} catch (Exception e) {
			throw new IllegalStateException(
					"Plugin broker communication failed",
					e
			);
		}
	}


	private String readBoundedLine(
			InputStream input
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

			if (output.size()
					>= MAX_BROKER_RESPONSE_BYTES) {
				throw new IllegalStateException(
						"Plugin broker response is too large"
				);
			}

			output.write(value);
		}

		return output.toString(
				StandardCharsets.UTF_8
		);
	}
}
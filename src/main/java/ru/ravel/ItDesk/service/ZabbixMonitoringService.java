package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.ZabbixIntegrationRequest;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;
import ru.ravel.ItDesk.model.ZabbixIntegration;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.ZabbixIntegrationRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class ZabbixMonitoringService {

	private static final int MAX_ZABBIX_RESPONSE_BYTES = 2 * 1024 * 1024;

	private final ZabbixIntegrationRepository integrationRepository;
	private final ServiceRepository serviceRepository;
	private final ObjectMapper objectMapper;
	private final GlobalSearchService globalSearchService;
	private final AtomicLong requestId = new AtomicLong(1);
	private final HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(15))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();

	@Transactional(readOnly = true)
	public List<Map<String, Object>> getIntegrations() {
		return integrationRepository.findAll().stream()
				.sorted(Comparator.comparing(item -> Objects.toString(item.getName(), ""), String.CASE_INSENSITIVE_ORDER))
				.map(this::toDto)
				.toList();
	}

	@Transactional
	public Map<String, Object> createIntegration(ZabbixIntegrationRequest request) {
		validate(request, null);
		ZabbixIntegration integration = new ZabbixIntegration();
		apply(integration, request, false);
		return toDto(integrationRepository.save(integration));
	}

	@Transactional
	public Map<String, Object> updateIntegration(Long id, ZabbixIntegrationRequest request) {
		validate(request, id);
		ZabbixIntegration integration = integrationRepository.findById(id).orElseThrow();
		apply(integration, request, true);
		return toDto(integrationRepository.save(integration));
	}

	@Transactional
	public void deleteIntegration(Long id) {
		if (serviceRepository.existsByZabbixIntegrationId(id)) {
			throw new IllegalStateException("Интеграция используется сервисами. Сначала отключите её в карточках сервисов.");
		}
		integrationRepository.deleteById(id);
	}

	@Transactional
	public Map<String, Object> testIntegration(Long id) {
		ZabbixIntegration integration = integrationRepository.findById(id).orElseThrow();
		ZonedDateTime now = ZonedDateTime.now();
		try {
			String version = call(integration, "apiinfo.version", Map.of(), false).asText();
			JsonNode hosts = call(integration, "host.get", Map.of("output", List.of("hostid"), "limit", 1), true);
			String message = "Соединение установлено, Zabbix " + version + ", доступ к API подтверждён";
			integration.setLastTestedAt(now);
			integration.setLastTestSuccess(true);
			integration.setLastTestMessage(message);
			integrationRepository.save(integration);
			return Map.of("success", true, "version", version, "hostAccess", hosts.isArray(), "message", message);
		} catch (Exception exception) {
			String message = safeMessage(exception);
			integration.setLastTestedAt(now);
			integration.setLastTestSuccess(false);
			integration.setLastTestMessage(message);
			integrationRepository.save(integration);
			return Map.of("success", false, "message", message);
		}
	}

	@Transactional
	public Map<String, Object> checkService(Long serviceId) {
		ru.ravel.ItDesk.model.Service catalogService = serviceRepository.findById(serviceId).orElseThrow();
		ZonedDateTime now = ZonedDateTime.now();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("serviceId", catalogService.getId());
		result.put("checkedAt", now);
		if (!Boolean.TRUE.equals(catalogService.getZabbixEnabled()) || catalogService.getZabbixIntegration() == null) {
			updateMonitoring(catalogService, ServiceMonitoringStatus.NOT_CONFIGURED, "Мониторинг Zabbix не настроен", Map.of());
			return monitoringDto(catalogService);
		}
		ZabbixIntegration integration = catalogService.getZabbixIntegration();
		if (Boolean.FALSE.equals(integration.getActive())) {
			updateMonitoring(catalogService, ServiceMonitoringStatus.UNAVAILABLE, "Интеграция Zabbix отключена", Map.of());
			return monitoringDto(catalogService);
		}
		try {
			List<String> hostIds = resolveHostIds(integration, catalogService);
			if (hostIds.isEmpty()) {
				updateMonitoring(catalogService, ServiceMonitoringStatus.UNAVAILABLE, "В Zabbix не найдены узлы сервиса", Map.of("hostCount", 0));
				return monitoringDto(catalogService);
			}
			Map<String, Object> params = new LinkedHashMap<>();
			params.put("output", List.of("eventid", "objectid", "name", "severity", "clock", "acknowledged"));
			params.put("hostids", hostIds);
			params.put("sortfield", List.of("eventid"));
			params.put("sortorder", "DESC");
			params.put("limit", 100);
			JsonNode problems = call(integration, "problem.get", params, true);
			int highestSeverity = 0;
			List<Map<String, Object>> problemRows = new ArrayList<>();
			if (problems.isArray()) {
				for (JsonNode problem : problems) {
					int severity = parseInt(problem.path("severity").asText("0"), 0);
					highestSeverity = Math.max(highestSeverity, severity);
					if (problemRows.size() < 20) {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("eventId", problem.path("eventid").asText());
						row.put("name", problem.path("name").asText());
						row.put("severity", severity);
						row.put("clock", problem.path("clock").asText());
						row.put("acknowledged", "1".equals(problem.path("acknowledged").asText()));
						problemRows.add(row);
					}
				}
			}
			ServiceMonitoringStatus status = highestSeverity >= 4
					? ServiceMonitoringStatus.CRITICAL
					: highestSeverity >= 2 ? ServiceMonitoringStatus.WARNING : ServiceMonitoringStatus.OK;
			Map<String, Object> data = new LinkedHashMap<>();
			data.put("hostIds", hostIds);
			data.put("hostCount", hostIds.size());
			data.put("problemCount", problems.isArray() ? problems.size() : 0);
			data.put("highestSeverity", highestSeverity);
			data.put("problems", problemRows);
			String message = problemRows.isEmpty()
					? "Zabbix: активных проблем нет"
					: "Zabbix: активных проблем %d, максимальная важность %d".formatted(problems.size(), highestSeverity);
			updateMonitoring(catalogService, status, message, data);
			return monitoringDto(catalogService);
		} catch (Exception exception) {
			log.warn("Zabbix service check failed for serviceId={}", serviceId, exception);
			updateMonitoring(catalogService, ServiceMonitoringStatus.UNAVAILABLE, safeMessage(exception), Map.of());
			return monitoringDto(catalogService);
		}
	}

	@Transactional(readOnly = true)
	public Map<String, Object> getMonitoringSummary() {
		Map<String, Object> result = new LinkedHashMap<>();
		for (ServiceMonitoringStatus status : ServiceMonitoringStatus.values()) {
			result.put(status.name(), serviceRepository.countByMonitoringStatus(status));
		}
		return result;
	}

	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	public void pollEnabledServices() {
		for (ru.ravel.ItDesk.model.Service service : serviceRepository.findAllByZabbixEnabledTrueAndActiveTrue()) {
			try {
				checkService(service.getId());
			} catch (Exception exception) {
				log.warn("Scheduled Zabbix check failed for serviceId={}", service.getId(), exception);
			}
		}
	}

	private List<String> resolveHostIds(ZabbixIntegration integration, ru.ravel.ItDesk.model.Service catalogService) throws Exception {
		Map<String, String> identifiers = Objects.requireNonNullElseGet(catalogService.getMonitoringIdentifiers(), LinkedHashMap::new);
		LinkedHashSet<String> directIds = new LinkedHashSet<>();
		directIds.addAll(splitIdentifiers(identifiers.get("hostId")));
		directIds.addAll(splitIdentifiers(identifiers.get("hostIds")));
		if (!directIds.isEmpty()) return new ArrayList<>(directIds);

		Map<String, Object> params = new LinkedHashMap<>();
		params.put("output", List.of("hostid", "host", "name", "status", "available"));
		params.put("monitored_hosts", true);
		List<String> groupIds = splitIdentifiers(firstNonBlank(identifiers.get("hostGroupIds"), identifiers.get("groupIds")));
		if (!groupIds.isEmpty()) params.put("groupids", groupIds);
		List<String> hostNames = splitIdentifiers(firstNonBlank(identifiers.get("hostNames"), identifiers.get("hosts")));
		if (hostNames.isEmpty() && groupIds.isEmpty()) {
			hostNames = java.util.stream.Stream.of(catalogService.getCode(), catalogService.getName())
					.filter(Objects::nonNull)
					.map(String::trim)
					.filter(value -> !value.isBlank())
					.toList();
		}
		Set<String> requestedNames = hostNames.stream()
				.map(value -> value.toLowerCase(Locale.ROOT))
				.collect(java.util.stream.Collectors.toSet());
		JsonNode hosts = call(integration, "host.get", params, true);
		List<String> result = new ArrayList<>();
		if (hosts.isArray()) {
			for (JsonNode host : hosts) {
				String technicalName = host.path("host").asText("").trim().toLowerCase(Locale.ROOT);
				String visibleName = host.path("name").asText("").trim().toLowerCase(Locale.ROOT);
				if (!requestedNames.isEmpty() && !requestedNames.contains(technicalName) && !requestedNames.contains(visibleName)) continue;
				String id = host.path("hostid").asText();
				if (!id.isBlank()) result.add(id);
			}
		}
		return result;
	}

	private JsonNode call(ZabbixIntegration integration, String method, Object params, boolean authenticated) throws Exception {
		String authToken = null;
		boolean authorizationHeader = false;

		if (authenticated) {
			if (integration.hasApiToken()) {
				authToken = integration.getApiToken();
				authorizationHeader = true;
			} else {
				String apiVersion = getApiVersion(integration);
				authToken = login(integration, apiVersion);
				// The JSON-RPC `auth` property was removed in Zabbix 7.2.
				authorizationHeader = isVersionAtLeast(apiVersion, 7, 2);
			}
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("method", method);
		body.put("params", params == null ? Map.of() : params);
		body.put("id", requestId.getAndIncrement());
		if (authenticated && authToken != null && !authorizationHeader) body.put("auth", authToken);

		JsonNode response = send(integration, body, authorizationHeader ? authToken : null);
		if (response.hasNonNull("error")) {
			throw new IllegalStateException("Zabbix API: " + formatApiError(response));
		}
		return response.path("result");
	}

	private String getApiVersion(ZabbixIntegration integration) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("method", "apiinfo.version");
		body.put("params", Map.of());
		body.put("id", requestId.getAndIncrement());

		JsonNode response = send(integration, body, null);
		if (response.hasNonNull("error")) {
			throw new IllegalStateException("Не удалось определить версию Zabbix: " + formatApiError(response));
		}

		String version = response.path("result").asText("").trim();
		if (version.isBlank()) throw new IllegalStateException("Zabbix не вернул версию API");
		return version;
	}

	private String login(ZabbixIntegration integration, String apiVersion) throws Exception {
		if (integration.getUsername() == null || integration.getUsername().isBlank() || !integration.hasPassword()) {
			throw new IllegalStateException("Для интеграции не задан API token или логин/пароль");
		}

		// Zabbix 5.4+ accepts `username`; Zabbix 5.0 and older require `user`.
		String usernameField = isVersionAtLeast(apiVersion, 5, 4) ? "username" : "user";
		JsonNode response = loginRequest(integration, usernameField);
		if (response.hasNonNull("error")) {
			throw new IllegalStateException("Zabbix authentication failed: " + formatApiError(response));
		}
		return requireLoginToken(response);
	}

	private JsonNode loginRequest(ZabbixIntegration integration, String usernameField) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("jsonrpc", "2.0");
		body.put("method", "user.login");
		body.put("params", Map.of(usernameField, integration.getUsername(), "password", integration.getPassword()));
		body.put("id", requestId.getAndIncrement());
		return send(integration, body, null);
	}

	private String requireLoginToken(JsonNode response) {
		String token = response.path("result").asText();
		if (token.isBlank()) throw new IllegalStateException("Zabbix не вернул токен сессии");
		return token;
	}

	private JsonNode send(ZabbixIntegration integration, Map<String, Object> body, String bearerToken) throws Exception {
		String url = normalizeApiUrl(integration.getBaseUrl());
		int timeout = Math.max(2, Math.min(120, Objects.requireNonNullElse(integration.getRequestTimeoutSeconds(), 15)));
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(timeout))
				.header("Content-Type", "application/json-rpc")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
		if (bearerToken != null && !bearerToken.isBlank()) {
			builder.header("Authorization", "Bearer " + bearerToken);
		}
		HttpResponse<InputStream> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
		String responseBody;
		try (InputStream bodyStream = response.body()) {
			byte[] bytes = bodyStream.readNBytes(MAX_ZABBIX_RESPONSE_BYTES + 1);
			if (bytes.length > MAX_ZABBIX_RESPONSE_BYTES) {
				throw new IllegalStateException("Zabbix response exceeds 2 MiB");
			}
			responseBody = new String(bytes, StandardCharsets.UTF_8);
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw new IllegalStateException("Zabbix HTTP " + response.statusCode() + ": " + truncate(responseBody, 500));
		}
		return objectMapper.readTree(responseBody);
	}

	private boolean isVersionAtLeast(String version, int requiredMajor, int requiredMinor) {
		try {
			String[] parts = Objects.toString(version, "").split("\\.");
			int major = parts.length > 0 ? Integer.parseInt(parts[0].replaceAll("[^0-9]", "")) : 0;
			int minor = parts.length > 1 ? Integer.parseInt(parts[1].replaceAll("[^0-9]", "")) : 0;
			return major > requiredMajor || major == requiredMajor && minor >= requiredMinor;
		} catch (Exception ignored) {
			// Unknown future version formats should use the modern API contract.
			return true;
		}
	}

	private String formatApiError(JsonNode response) {
		JsonNode error = response.path("error");
		String message = error.path("message").asText("").trim();
		String data = error.path("data").asText("").trim();
		if (message.isBlank()) return data.isBlank() ? "неизвестная ошибка" : data;
		return data.isBlank() ? message : message + ": " + data;
	}

	private void updateMonitoring(ru.ravel.ItDesk.model.Service catalogService, ServiceMonitoringStatus status, String message, Map<String, Object> data) {
		catalogService.setMonitoringStatus(status);
		catalogService.setLastMonitoringAt(ZonedDateTime.now());
		catalogService.setLastMonitoringMessage(truncate(message, 2000));
		catalogService.setLastMonitoringData(data == null ? new LinkedHashMap<>() : new LinkedHashMap<>(data));
		serviceRepository.save(catalogService);
		globalSearchService.indexService(catalogService);
	}

	private Map<String, Object> monitoringDto(ru.ravel.ItDesk.model.Service catalogService) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("serviceId", catalogService.getId());
		result.put("status", catalogService.getMonitoringStatus());
		result.put("checkedAt", catalogService.getLastMonitoringAt());
		result.put("message", catalogService.getLastMonitoringMessage());
		result.put("data", Objects.requireNonNullElseGet(catalogService.getLastMonitoringData(), LinkedHashMap::new));
		return result;
	}

	private void validate(ZabbixIntegrationRequest request, Long id) {
		if (request == null || request.getName() == null || request.getName().isBlank()) throw new IllegalArgumentException("Название интеграции обязательно");
		if (request.getBaseUrl() == null || request.getBaseUrl().isBlank()) throw new IllegalArgumentException("URL Zabbix обязателен");
		URI uri = URI.create(normalizeApiUrl(request.getBaseUrl())).normalize();
		if (!Set.of("http", "https").contains(uri.getScheme())
				|| uri.getHost() == null
				|| uri.getUserInfo() != null
				|| uri.getFragment() != null) {
			throw new IllegalArgumentException("Некорректный Zabbix URL: разрешены только http/https host URL без userinfo/fragment");
		}
		if (integrationRepository.existsByNameIgnoreCaseAndIdNot(request.getName().trim(), id == null ? -1L : id)) {
			throw new IllegalArgumentException("Интеграция с таким названием уже существует");
		}
	}

	private void apply(ZabbixIntegration integration, ZabbixIntegrationRequest request, boolean preserveSecrets) {
		integration.setName(request.getName().trim());
		integration.setBaseUrl(normalizeApiUrl(request.getBaseUrl()));
		integration.setUsername(blankToNull(request.getUsername()));
		if (!preserveSecrets || request.getPassword() != null && !request.getPassword().isBlank()) integration.setPassword(blankToNull(request.getPassword()));
		if (!preserveSecrets || request.getApiToken() != null && !request.getApiToken().isBlank()) integration.setApiToken(blankToNull(request.getApiToken()));
		integration.setActive(!Boolean.FALSE.equals(request.getActive()));
		integration.setRequestTimeoutSeconds(Math.max(2, Math.min(120, Objects.requireNonNullElse(request.getRequestTimeoutSeconds(), 15))));
	}

	private Map<String, Object> toDto(ZabbixIntegration integration) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", integration.getId());
		result.put("name", integration.getName());
		result.put("baseUrl", integration.getBaseUrl());
		result.put("username", integration.getUsername());
		result.put("active", integration.getActive());
		result.put("requestTimeoutSeconds", integration.getRequestTimeoutSeconds());
		result.put("hasPassword", integration.hasPassword());
		result.put("hasApiToken", integration.hasApiToken());
		result.put("lastTestedAt", integration.getLastTestedAt());
		result.put("lastTestSuccess", integration.getLastTestSuccess());
		result.put("lastTestMessage", integration.getLastTestMessage());
		return result;
	}

	private String normalizeApiUrl(String value) {
		String url = Objects.toString(value, "").trim().replaceAll("/+$", "");
		return url.endsWith("api_jsonrpc.php") ? url : url + "/api_jsonrpc.php";
	}

	private List<String> splitIdentifiers(String value) {
		if (value == null || value.isBlank()) return List.of();
		return Arrays.stream(value.split("[,;\\s]+"))
				.map(String::trim).filter(item -> !item.isBlank()).distinct().toList();
	}

	private String firstNonBlank(String... values) {
		for (String value : values) if (value != null && !value.isBlank()) return value;
		return null;
	}

	private int parseInt(String value, int fallback) {
		try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
	}

	private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
	private String safeMessage(Exception exception) { return truncate(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), 2000); }
	private String truncate(String value, int limit) { String text = Objects.toString(value, ""); return text.length() <= limit ? text : text.substring(0, limit); }
}

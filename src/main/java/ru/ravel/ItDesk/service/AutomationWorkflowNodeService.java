package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.dto.AutomationWorkflowDefinition;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;


@Slf4j
@Service
@RequiredArgsConstructor
public class AutomationWorkflowNodeService {

	private final ObjectMapper objectMapper;
	private final AutomationWorkflowValueService valueService;
	private final OutboundUrlGuard outboundUrlGuard;
	private final AutomationScriptRuntime scriptRuntime;
	private final AppSettingsService appSettingsService;
	private final AutomationThrottleRecordRepository throttleRepository;
	private final TaskRepository taskRepository;
	private final ClientRepository clientRepository;
	private final TaskService taskService;
	private final PriorityRepository priorityRepository;
	private final StatusRepository statusRepository;
	private final TaskTypeRepository taskTypeRepository;
	private final SupportLineRepository supportLineRepository;
	private final ServiceRepository serviceRepository;

	private static final int MAX_HTTP_RESPONSE_BYTES = 1024 * 1024;
	private static final int MAX_HTTP_REDIRECTS = 3;
	private static final Set<String> ALLOWED_HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD");
	private static final Set<Integer> HTTP_REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
	private static final Set<String> SENSITIVE_REDIRECT_HEADERS = Set.of("authorization", "proxy-authorization", "cookie");
	private static final Set<String> FORBIDDEN_REQUEST_HEADERS = Set.of(
			"host", "content-length", "connection", "transfer-encoding", "upgrade", "te", "trailer"
	);

	private final HttpClient httpClient = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(15))
			.build();


	public HttpResult http(AutomationWorkflowDefinition.Node node, Event event, boolean dryRun) {
		String method = configString(node, "method", "GET").toUpperCase(Locale.ROOT);
		if (!ALLOWED_HTTP_METHODS.contains(method)) {
			throw new IllegalArgumentException("HTTP method is not allowed");
		}
		String url = valueService.renderTemplate(event.getPayload(), configString(node, "url", ""));
		URI initialUri = outboundUrlGuard.validate(url);
		String body = valueService.renderTemplate(event.getPayload(), configString(node, "body", ""));
		int timeout = Math.max(1, Math.min(120, (int) configLong(node, "timeoutSeconds", 15)));
		String variable = configString(node, "resultVariable", "httpResponse");

		if (dryRun) {
			return new HttpResult(true, 0, "DRY_RUN", method + " " + initialUri);
		}

		try {
			Map<String, String> originalHeaders = safeHttpHeaders(
					parseHeaders(configString(node, "headersText", "{}"), event.getPayload())
			);
			URI currentUri = initialUri;
			String currentMethod = method;
			String currentBody = body;

			for (int redirectCount = 0; ; redirectCount++) {
				// Resolve again immediately before every connect. This reduces the DNS
				// rebinding window; network-level egress filtering remains recommended.
				outboundUrlGuard.validateResolvedAddresses(currentUri);
				HttpRequest request = buildAutomationHttpRequest(
						currentUri,
						currentMethod,
						currentBody,
						filterRedirectHeaders(originalHeaders, initialUri, currentUri),
						timeout
				);
				HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
				String responseBody;
				try (InputStream input = response.body()) {
					byte[] bytes = input.readNBytes(MAX_HTTP_RESPONSE_BYTES + 1);
					if (bytes.length > MAX_HTTP_RESPONSE_BYTES) {
						throw new IllegalStateException("HTTP response exceeds 1 MiB limit");
					}
					responseBody = new String(bytes, StandardCharsets.UTF_8);
				}

				if (HTTP_REDIRECT_STATUSES.contains(response.statusCode())
						&& response.headers().firstValue("Location").isPresent()) {
					if (redirectCount >= MAX_HTTP_REDIRECTS) {
						throw new IllegalStateException("Too many HTTP redirects");
					}
					URI nextUri = outboundUrlGuard.validate(
							currentUri.resolve(response.headers().firstValue("Location").orElseThrow()).toString()
					);
					if ("https".equalsIgnoreCase(currentUri.getScheme())
							&& "http".equalsIgnoreCase(nextUri.getScheme())) {
						throw new IllegalStateException("HTTPS to HTTP redirect is forbidden");
					}
					if (response.statusCode() == 303
							|| ((response.statusCode() == 301 || response.statusCode() == 302)
							&& "POST".equals(currentMethod))) {
						currentMethod = "GET";
						currentBody = "";
					}
					currentUri = nextUri;
					continue;
				}

				Map<String, Object> result = new LinkedHashMap<>();
				result.put("status", response.statusCode());
				result.put("body", responseBody);
				result.put("headers", response.headers().map());
				valueService.setVariable(event.getPayload(), variable, result);
				boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
				return new HttpResult(success, response.statusCode(), responseBody, method + " " + initialUri);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			valueService.setVariable(event.getPayload(), variable, Map.of("error", "HTTP request interrupted"));
			return new HttpResult(false, 0, "HTTP request interrupted", method + " " + initialUri);
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			valueService.setVariable(event.getPayload(), variable, Map.of("error", message));
			return new HttpResult(false, 0, message, method + " " + initialUri);
		}
	}


	private HttpRequest buildAutomationHttpRequest(
			URI uri,
			String method,
			String body,
			Map<String, String> headers,
			int timeoutSeconds
	) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(timeoutSeconds));
		headers.forEach(builder::header);
		HttpRequest.BodyPublisher publisher = body == null || body.isBlank() || "GET".equals(method) || "HEAD".equals(method)
				? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
		return builder.method(method, publisher).build();
	}


	private Map<String, String> safeHttpHeaders(Map<String, String> headers) {
		if (headers == null || headers.isEmpty()) {
			return Map.of();
		}
		Map<String, String> result = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			if (name == null || value == null) {
				return;
			}
			String trimmedName = name.trim();
			String lower = trimmedName.toLowerCase(Locale.ROOT);
			if (trimmedName.isBlank()
					|| !trimmedName.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
					|| FORBIDDEN_REQUEST_HEADERS.contains(lower)
					|| value.indexOf('\r') >= 0
					|| value.indexOf('\n') >= 0) {
				return;
			}
			result.put(trimmedName, value);
		});
		return result;
	}


	private Map<String, String> filterRedirectHeaders(Map<String, String> headers, URI initialUri, URI currentUri) {
		if (outboundUrlGuard.sameOrigin(initialUri, currentUri)) {
			return headers;
		}
		Map<String, String> result = new LinkedHashMap<>();
		headers.forEach((name, value) -> {
			if (!SENSITIVE_REDIRECT_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
				result.put(name, value);
			}
		});
		return result;
	}


	@Transactional
	public boolean throttle(AutomationTrigger trigger, AutomationWorkflowDefinition.Node node, Event event, boolean dryRun) {
		if (trigger == null || trigger.getId() == null) return true;
		long seconds = durationSeconds(configLong(node, "amount", 1), configString(node, "unit", "MINUTES"));
		String scope = configString(node, "scope", "TASK");
		String key = valueService.correlationKey(event.getPayload(), scope, configString(node, "customKey", "global"));
		Optional<AutomationThrottleRecord> existing = throttleRepository.findByTriggerIdAndNodeIdAndScopeKey(trigger.getId(), node.getId(), key);
		Instant now = Instant.now();
		boolean allowed = existing.isEmpty() || existing.get().getLastExecutedAt().plusSeconds(seconds).isBefore(now)
				|| existing.get().getLastExecutedAt().plusSeconds(seconds).equals(now);
		if (allowed && !dryRun) {
			AutomationThrottleRecord record = existing.orElseGet(() -> AutomationThrottleRecord.builder()
					.triggerId(trigger.getId())
					.nodeId(node.getId())
					.scopeKey(key)
					.build());
			record.setLastExecutedAt(now);
			throttleRepository.save(record);
		}
		return allowed;
	}


	@Transactional(readOnly = true)
	public Task findDuplicate(AutomationWorkflowDefinition.Node node, Event event) {
		Long clientId = valueService.resolveLong(event.getPayload(), "client.id");
		if (clientId == null) return null;
		Long currentTaskId = valueService.resolveLong(event.getPayload(), "task.id");
		Long typeId = configNullableLong(node, "typeId");
		long windowMinutes = Math.max(1L, configLong(node, "windowMinutes", 120L));
		String mode = configString(node, "titleMode", "CURRENT").toUpperCase(Locale.ROOT);
		String title = configString(node, "title", "");
		if ("CURRENT".equals(mode)) title = valueService.resolveString(event.getPayload(), "task.name");
		if (title == null) title = "";
		boolean exact = "EXACT".equals(mode) || "CURRENT".equals(mode);
		boolean contains = "CONTAINS".equals(mode);
		List<Task> matches = taskRepository.findPotentialDuplicates(
				clientId,
				currentTaskId,
				typeId,
				ZonedDateTime.now().minusMinutes(windowMinutes),
				exact,
				contains,
				title,
				PageRequest.of(0, 1)
		);
		Task duplicate = matches.isEmpty() ? null : matches.get(0);
		String variable = configString(node, "resultVariable", "duplicateTaskId");
		valueService.setVariable(event.getPayload(), variable, duplicate == null ? null : duplicate.getId());
		return duplicate;
	}


	@Transactional
	public Long createTask(AutomationWorkflowDefinition.Node node, Event event, boolean dryRun) {
		node = resolveLegacyValues(node, event, Set.of("title", "description", "typeId", "serviceId", "priorityName", "supportLineId"));
		Long clientId = valueService.resolveLong(event.getPayload(), "client.id");
		if (clientId == null) throw new IllegalStateException("В событии отсутствует client.id");
		String title = configString(node, "title", "Связанная заявка");
		String description = configString(node, "description", "");
		String variable = configString(node, "resultVariable", "createdTaskId");
		if (dryRun) {
			valueService.setVariable(event.getPayload(), variable, -1L);
			return -1L;
		}
		Task.TaskBuilder builder = Task.builder()
				.name(title)
				.description(description)
				.status(statusRepository.findByDefaultSelectionTrue().orElseThrow())
				.priority(resolvePriority(node));
		Long typeId = configNullableLong(node, "typeId");
		if (typeId != null) builder.type(taskTypeRepository.findById(typeId).orElseThrow());
		Long serviceId = configNullableLong(node, "serviceId");
		if (serviceId != null) builder.service(serviceRepository.findById(serviceId).orElseThrow());
		Long lineId = configNullableLong(node, "supportLineId");
		if (lineId != null) builder.supportLine(supportLineRepository.findById(lineId).orElseThrow());
		Task task = taskService.newTaskForSystem(clientId, builder.build());
		valueService.setVariable(event.getPayload(), variable, task.getId());
		return task.getId();
	}


	public void escalate(AutomationTrigger trigger, AutomationWorkflowDefinition.Node node, Event event, boolean dryRun) {
		node = resolveLegacyValues(node, event, Set.of("supportLineId", "priorityName", "clearAssignee", "notifyUserId", "reason"));
		Long lineId = configNullableLong(node, "supportLineId");
		if (lineId == null) throw new IllegalArgumentException("Не выбрана линия эскалации");
		if (dryRun) return;
		AutomationExecutionContext context = new AutomationExecutionContext(trigger, event);
		scriptRuntime.executeResolvedAction("task.assignToGroup", List.of(lineId), context);
		if (configBoolean(node, "clearAssignee", true)) scriptRuntime.executeResolvedAction("task.clearAssignee", List.of(), context);
		String priority = configString(node, "priorityName", null);
		if (priority != null && !priority.isBlank()) scriptRuntime.executeResolvedAction("task.setPriority", List.of(priority), context);
		Long userId = configNullableLong(node, "notifyUserId");
		String reason = configString(node, "reason", "Заявка эскалирована");
		if (userId != null && reason != null && !reason.isBlank()) scriptRuntime.executeResolvedAction("notify.user", List.of(userId,reason), context);
	}


	public void notify(AutomationTrigger trigger, AutomationWorkflowDefinition.Node node, Event event, boolean dryRun) {
		node = resolveLegacyValues(node, event, Set.of("channel", "userId", "text"));
		String channel = configString(node, "channel", "CLIENT").toUpperCase(Locale.ROOT);
		String text = configString(node, "text", "");
		if (text == null || text.isBlank()) throw new IllegalArgumentException("Текст уведомления пуст");
		Long userId = configNullableLong(node, "userId");
		if ("USER".equals(channel) && userId == null) throw new IllegalArgumentException("Не выбран пользователь");
		if ("LOG".equals(channel)) {
			log.info("AUTOMATION NOTIFY triggerId={}, text={}", trigger == null ? null : trigger.getId(), text);
		} else if (!dryRun) {
			scriptRuntime.executeResolvedAction("USER".equals(channel) ? "notify.user" : "client.sendMessage",
					"USER".equals(channel) ? List.of(userId,text) : List.of(text), new AutomationExecutionContext(trigger,event));
		}
	}


	private AutomationWorkflowDefinition.Node resolveLegacyValues(AutomationWorkflowDefinition.Node node, Event event, Set<String> fields) {
		Map<String, Object> config = new LinkedHashMap<>(node.getConfig());
		for (String field : fields) {
			Object value = config.get(field);
			if (value instanceof String text) {
				config.put(field, text.startsWith("=") ? scriptRuntime.evaluateValue(text.substring(1), event.getPayload())
						: valueService.renderTemplate(event.getPayload(), text));
			}
		}
		return AutomationWorkflowDefinition.Node.builder().id(node.getId()).type(node.getType()).label(node.getLabel())
				.x(node.getX()).y(node.getY()).config(config).build();
	}


	public BusinessPeriod businessPeriod() {
		AppSettings settings;
		try {
			settings = appSettingsService.getGeneralSettings();
		} catch (Exception e) {
			return BusinessPeriod.WORKING;
		}
		ZoneId zone;
		try {
			zone = settings == null || settings.getTimezone() == null ? ZoneId.systemDefault() : ZoneId.of(settings.getTimezone());
		} catch (Exception e) {
			zone = ZoneId.systemDefault();
		}
		ZonedDateTime now = ZonedDateTime.now(zone);
		if (settings == null || !Boolean.TRUE.equals(settings.getWorkingTimeEnabled())) return BusinessPeriod.WORKING;
		if (!isWorkingDay(settings, now.getDayOfWeek())) return BusinessPeriod.WEEKEND;
		LocalTime start = parseTime(settings.getWorkdayStart(), LocalTime.of(9, 0));
		LocalTime end = parseTime(settings.getWorkdayEnd(), LocalTime.of(18, 0));
		LocalTime time = now.toLocalTime();
		return !time.isBefore(start) && time.isBefore(end) ? BusinessPeriod.WORKING : BusinessPeriod.AFTER_HOURS;
	}


	public Instant waitUntil(AutomationWorkflowDefinition.Node node, Event event) {
		String mode = configString(node, "mode", "DURATION").toUpperCase(Locale.ROOT);
		Instant now = Instant.now();
		return switch (mode) {
			case "FIXED" -> parseDateTime(configString(node, "dateTime", null));
			case "TASK_DEADLINE" -> readDeadline(event);
			case "TASK_DEADLINE_MINUS" -> readDeadline(event).minusSeconds(Math.max(0L, configLong(node, "minutesBefore", 0L)) * 60L);
			case "NEXT_WORKDAY" -> nextWorkdayStart();
			default -> now.plusSeconds(durationSeconds(configLong(node, "amount", 1L), configString(node, "unit", "MINUTES")));
		};
	}


	public long durationSeconds(long amount, String unit) {
		long safeAmount = Math.max(1L, amount);
		return switch (unit == null ? "MINUTES" : unit.toUpperCase(Locale.ROOT)) {
			case "SECONDS" -> safeAmount;
			case "HOURS" -> Math.multiplyExact(safeAmount, 3600L);
			case "DAYS" -> Math.multiplyExact(safeAmount, 86400L);
			default -> Math.multiplyExact(safeAmount, 60L);
		};
	}


	private Map<String, String> parseHeaders(String json, JsonNode payload) throws Exception {
		String rendered = valueService.renderTemplate(payload, json == null || json.isBlank() ? "{}" : json);
		Map<String, Object> raw = objectMapper.readValue(rendered, new TypeReference<>() {});
		Map<String, String> result = new LinkedHashMap<>();
		raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
		return result;
	}


	private Priority resolvePriority(AutomationWorkflowDefinition.Node node) {
		String priorityName = configString(node, "priorityName", null);
		return priorityName == null || priorityName.isBlank()
				? priorityRepository.findByDefaultSelectionTrue().orElseThrow()
				: priorityRepository.findByName(priorityName).orElseThrow();
	}


	private Instant readDeadline(Event event) {
		JsonNode deadline = valueService.readPath(event.getPayload(), "task.deadline");
		if (deadline == null || deadline.isNull()) throw new IllegalStateException("У заявки отсутствует дедлайн");
		return parseDateTime(deadline.asText());
	}


	private Instant parseDateTime(String value) {
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Дата и время не заполнены");
		try { return Instant.parse(value); } catch (Exception ignored) {}
		try { return ZonedDateTime.parse(value).toInstant(); } catch (Exception ignored) {}
		try { return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant(); } catch (Exception ignored) {}
		throw new IllegalArgumentException("Некорректная дата: " + value);
	}


	private Instant nextWorkdayStart() {
		AppSettings settings;
		try {
			settings = appSettingsService.getGeneralSettings();
		} catch (Exception ignored) {
			settings = null;
		}
		ZoneId zone;
		try {
			zone = settings == null || settings.getTimezone() == null
					? ZoneId.systemDefault()
					: ZoneId.of(settings.getTimezone());
		} catch (Exception e) {
			zone = ZoneId.systemDefault();
		}
		LocalTime start = parseTime(settings == null ? null : settings.getWorkdayStart(), LocalTime.of(9, 0));
		LocalDate date = LocalDate.now(zone).plusDays(1);
		for (int i = 0; i < 14 && !isWorkingDay(settings, date.getDayOfWeek()); i++) date = date.plusDays(1);
		return date.atTime(start).atZone(zone).toInstant();
	}


	private boolean isWorkingDay(AppSettings settings, DayOfWeek day) {
		if (settings == null) {
			return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
		}
		return switch (day) {
			case MONDAY -> Boolean.TRUE.equals(settings.getMondayEnabled());
			case TUESDAY -> Boolean.TRUE.equals(settings.getTuesdayEnabled());
			case WEDNESDAY -> Boolean.TRUE.equals(settings.getWednesdayEnabled());
			case THURSDAY -> Boolean.TRUE.equals(settings.getThursdayEnabled());
			case FRIDAY -> Boolean.TRUE.equals(settings.getFridayEnabled());
			case SATURDAY -> Boolean.TRUE.equals(settings.getSaturdayEnabled());
			case SUNDAY -> Boolean.TRUE.equals(settings.getSundayEnabled());
		};
	}


	private LocalTime parseTime(String value, LocalTime fallback) {
		try { return value == null ? fallback : LocalTime.parse(value); } catch (Exception e) { return fallback; }
	}


	private String configString(AutomationWorkflowDefinition.Node node, String key, String fallback) {
		Object value = node == null || node.getConfig() == null ? null : node.getConfig().get(key);
		return value == null ? fallback : String.valueOf(value).trim();
	}


	private long configLong(AutomationWorkflowDefinition.Node node, String key, long fallback) {
		Object value = node == null || node.getConfig() == null ? null : node.getConfig().get(key);
		if (value instanceof Number number) return number.longValue();
		try { return value == null ? fallback : Long.parseLong(String.valueOf(value)); } catch (Exception e) { return fallback; }
	}


	private Long configNullableLong(AutomationWorkflowDefinition.Node node, String key) {
		Object value = node == null || node.getConfig() == null ? null : node.getConfig().get(key);
		if (value instanceof Number number) return number.longValue();
		try { return value == null || String.valueOf(value).isBlank() ? null : Long.parseLong(String.valueOf(value)); } catch (Exception e) { return null; }
	}


	private boolean configBoolean(AutomationWorkflowDefinition.Node node, String key, boolean fallback) {
		Object value = node == null || node.getConfig() == null ? null : node.getConfig().get(key);
		if (value instanceof Boolean bool) return bool;
		return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
	}




	public enum BusinessPeriod { WORKING, AFTER_HOURS, WEEKEND }
	public record HttpResult(boolean success, int status, String body, String request) {}
}

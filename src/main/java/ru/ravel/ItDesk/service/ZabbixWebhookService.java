package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.dto.MonitoringProblemEvent;
import ru.ravel.ItDesk.model.MonitoringProblem;
import ru.ravel.ItDesk.model.MonitoringWebhookLog;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import ru.ravel.ItDesk.model.ZabbixIntegration;
import ru.ravel.ItDesk.model.ZabbixIntegrationMode;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.MonitoringProblemRepository;
import ru.ravel.ItDesk.repository.MonitoringWebhookLogRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.UserRepository;
import ru.ravel.ItDesk.repository.ZabbixIntegrationRepository;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Приём событий Zabbix веб-хуком (режим WEBHOOK): Zabbix в закрытом контуре клиента сам отправляет события
 * в ULDesk. Открытые проблемы сервиса определяют его состояние мониторинга так же, как при опросе API.
 * Контракт — uldesk.zabbix.v1, тип оповещений для импорта в Zabbix — {@link ZabbixMediaTypeGenerator}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ZabbixWebhookService {

	public static final String TOKEN_HEADER = "X-ULDesk-Token";
	public static final String SCHEMA = "uldesk.zabbix.v1";
	public static final String SERVICE_TAG = "uldesk_service";
	public static final String API_PATH = "/api/v1/integrations/zabbix/";

	private static final DateTimeFormatter ZABBIX_DATE_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss");
	private static final int RETENTION_DAYS = 30;
	private static final int MAX_REQUESTS_PER_MINUTE = 300;
	private static final int PROBLEMS_IN_STATUS = 20;
	private static final Set<IncidentStatus> INACTIVE_INCIDENT = EnumSet.of(IncidentStatus.CLOSED, IncidentStatus.CANCELLED);

	private final ZabbixIntegrationRepository integrationRepository;
	private final MonitoringProblemRepository problemRepository;
	private final MonitoringWebhookLogRepository logRepository;
	private final ServiceRepository serviceRepository;
	private final IncidentRepository incidentRepository;
	private final EventPublisher eventPublisher;
	private final GlobalSearchService globalSearchService;
	private final UserNotificationService userNotificationService;
	private final UserRepository userRepository;
	private final AfterCommitExecutor afterCommitExecutor;

	private final SecureRandom random = new SecureRandom();
	private final Map<Long, RateWindow> rateWindows = new ConcurrentHashMap<>();


	/**
	 * Ответ на запрос Zabbix: HTTP-код и тело.
	 */
	public record Reply(int status, Map<String, Object> body) {

		static Reply error(int status, String message) {
			return new Reply(status, Map.of("accepted", false, "message", message));
		}
	}


	/**
	 * Выданный токен: показывается один раз, в БД хранится только его хеш.
	 */
	public record IssuedToken(String token, String eventsUrl, String heartbeatUrl) {
	}


	// ======================================================================
	// Токен и адреса
	// ======================================================================

	@Transactional
	public IssuedToken issueToken(Long integrationId) {
		ZabbixIntegration integration = integrationRepository.findById(integrationId).orElseThrow();
		if (integration.getWebhookKey() == null || integration.getWebhookKey().isBlank()) {
			integration.setWebhookKey(randomToken(18));
		}
		String token = randomToken(32);
		integration.setWebhookTokenHash(sha256(token));
		integration.setWebhookTokenIssuedAt(ZonedDateTime.now());
		integrationRepository.save(integration);
		return new IssuedToken(token, eventsUrl(integration), heartbeatUrl(integration));
	}


	/** Адрес приёма событий. Без внешнего адреса ULDesk — относительный путь. */
	public String eventsUrl(ZabbixIntegration integration) {
		return integration.getWebhookKey() == null ? null : baseUrl(integration) + API_PATH + integration.getWebhookKey() + "/events";
	}


	public String heartbeatUrl(ZabbixIntegration integration) {
		return integration.getWebhookKey() == null ? null : baseUrl(integration) + API_PATH + integration.getWebhookKey() + "/heartbeat";
	}


	// ======================================================================
	// Приём событий
	// ======================================================================

	@Transactional
	public Reply receiveEvent(String key, String token, String remoteIp, JsonNode body) {
		ZonedDateTime now = ZonedDateTime.now();
		Optional<ZabbixIntegration> found = key == null ? Optional.empty() : integrationRepository.findByWebhookKey(key);
		if (found.isEmpty()) {
			return Reply.error(404, "Интеграция не найдена");
		}
		ZabbixIntegration integration = found.get();
		MonitoringWebhookLog entry = MonitoringWebhookLog.builder()
				.integrationId(integration.getId())
				.receivedAt(now)
				.kind(MonitoringWebhookLog.Kind.EVENT)
				.remoteIp(truncate(remoteIp, 64))
				.build();
		Reply denied = authorize(integration, token, remoteIp);
		if (denied != null) {
			return reject(entry, denied);
		}

		JsonNode event = body == null ? null : body.path("event");
		if (body == null || !body.isObject()) {
			return reject(entry, Reply.error(400, "Ожидается JSON-объект по контракту " + SCHEMA));
		}
		if (body.hasNonNull("schema") && !SCHEMA.equals(body.get("schema").asText())) {
			return reject(entry, Reply.error(400, "Неизвестная версия контракта «%s», ожидается %s"
					.formatted(body.get("schema").asText(), SCHEMA)));
		}
		String eventId = text(event, "id");
		String eventStatus = Optional.ofNullable(text(event, "status")).map(value -> value.toUpperCase(Locale.ROOT)).orElse(null);
		entry.setEventId(truncate(eventId, 64));
		entry.setEventStatus(truncate(eventStatus, 16));
		entry.setEventName(truncate(text(event, "name"), 1024));
		entry.setHost(truncate(text(body.path("host"), "host"), 255));
		if (eventId == null) {
			return reject(entry, Reply.error(400, "Не передан event.id"));
		}
		if (!Set.of("PROBLEM", "RESOLVED", "UPDATE").contains(eventStatus)) {
			return reject(entry, Reply.error(400, "event.status должен быть PROBLEM, RESOLVED или UPDATE"));
		}
		Integer severity = severity(event);
		entry.setSeverity(severity);
		markContact(integration, now, true);

		List<Map<String, String>> tags = tags(event.path("tags"));
		Optional<ru.ravel.ItDesk.model.Service> mapped = resolveService(integration, body, tags);
		MonitoringProblem problem = problemRepository.findByIntegrationIdAndEventId(integration.getId(), eventId).orElse(null);
		ru.ravel.ItDesk.model.Service previousService = problem == null ? null : problem.getService();

		TriggerType trigger;
		boolean duplicate = false;
		if (problem == null) {
			problem = MonitoringProblem.builder()
					.integration(integration)
					.eventId(truncate(eventId, 64))
					.receivedAt(now)
					.build();
			// Пропущенное начало проблемы (обновление или восстановление пришло первым) — всё равно учитываем
			problem.setStatus("RESOLVED".equals(eventStatus) ? MonitoringProblem.Status.RESOLVED : MonitoringProblem.Status.OPEN);
			trigger = "RESOLVED".equals(eventStatus) ? TriggerType.MONITORING_PROBLEM_RESOLVED : TriggerType.MONITORING_PROBLEM_STARTED;
		} else if ("PROBLEM".equals(eventStatus)) {
			// Повтор отправки или запоздавший PROBLEM после RESOLVED: состояние не меняем
			duplicate = true;
			trigger = null;
		} else if ("RESOLVED".equals(eventStatus)) {
			duplicate = problem.getStatus() == MonitoringProblem.Status.RESOLVED;
			problem.setStatus(MonitoringProblem.Status.RESOLVED);
			trigger = duplicate ? null : TriggerType.MONITORING_PROBLEM_RESOLVED;
		} else {
			trigger = TriggerType.MONITORING_PROBLEM_UPDATED;
		}

		if (!duplicate) {
			applyEvent(problem, body, event, eventStatus, severity, tags, integration, now);
		}
		if (mapped.isPresent()) {
			problem.setService(mapped.get());
		}
		problem.setUpdatedAt(now);
		problem = problemRepository.save(problem);

		ru.ravel.ItDesk.model.Service service = problem.getService();
		if (previousService != null && !Objects.equals(previousService.getId(), service == null ? null : service.getId())) {
			recompute(previousService);
		}
		if (service != null) {
			recompute(service);
		}
		if (trigger != null) {
			eventPublisher.publish(trigger, payload(problem, body, eventStatus));
		}

		MonitoringWebhookLog.Outcome outcome = duplicate
				? MonitoringWebhookLog.Outcome.DUPLICATE
				: service == null ? MonitoringWebhookLog.Outcome.UNMAPPED : MonitoringWebhookLog.Outcome.ACCEPTED;
		entry.setOutcome(outcome);
		entry.setServiceId(service == null ? null : service.getId());
		entry.setServiceCode(service == null ? null : truncate(service.getCode(), 80));
		entry.setMessage(switch (outcome) {
			case DUPLICATE -> "Повтор события, состояние не изменено";
			case UNMAPPED -> "Сервис не найден: добавьте тег " + SERVICE_TAG + "=<код сервиса> или узел в карточку сервиса";
			default -> "Сервис " + service.getCode() + ": " + service.getMonitoringStatus();
		});
		logRepository.save(entry);
		return new Reply(200, response(integration, problem, service, outcome));
	}


	/**
	 * Проверка связи: элемент данных «HTTP agent» в Zabbix раз в несколько минут обращается сюда.
	 */
	@Transactional
	public Reply heartbeat(String key, String token, String remoteIp) {
		Optional<ZabbixIntegration> found = key == null ? Optional.empty() : integrationRepository.findByWebhookKey(key);
		if (found.isEmpty()) {
			return Reply.error(404, "Интеграция не найдена");
		}
		ZabbixIntegration integration = found.get();
		Reply denied = authorize(integration, token, remoteIp);
		if (denied != null) {
			// Успешные проверки связи не пишем: их сотни в сутки
			return reject(MonitoringWebhookLog.builder()
					.integrationId(integration.getId())
					.receivedAt(ZonedDateTime.now())
					.kind(MonitoringWebhookLog.Kind.HEARTBEAT)
					.remoteIp(truncate(remoteIp, 64))
					.build(), denied);
		}
		markContact(integration, ZonedDateTime.now(), false);
		return new Reply(200, Map.of("ok", 1));
	}


	// ======================================================================
	// Состояние сервисов и связь
	// ======================================================================

	/**
	 * Состояние сервиса по открытым проблемам: важность 4–5 — CRITICAL, 2–3 — WARNING, иначе OK.
	 * Без связи с Zabbix — UNKNOWN: по тишине нельзя понять, что проблем нет.
	 */
	@Transactional
	public void recompute(ru.ravel.ItDesk.model.Service service) {
		ZabbixIntegration integration = service.getZabbixIntegration();
		if (integration == null || !integration.isWebhook()) {
			return;
		}
		if (Boolean.FALSE.equals(integration.getActive())) {
			update(service, ServiceMonitoringStatus.UNAVAILABLE, "Интеграция Zabbix отключена", Map.of("source", "WEBHOOK"));
			return;
		}
		List<MonitoringProblem> open = problemRepository
				.findAllByServiceIdAndStatusOrderBySeverityDescStartedAtDesc(service.getId(), MonitoringProblem.Status.OPEN);
		int highest = open.stream().mapToInt(problem -> Objects.requireNonNullElse(problem.getSeverity(), 0)).max().orElse(0);
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("source", "WEBHOOK");
		data.put("problemCount", open.size());
		data.put("highestSeverity", highest);
		data.put("problems", open.stream().limit(PROBLEMS_IN_STATUS).map(this::problemRow).toList());
		data.put("lastEventAt", integration.getLastEventAt());
		data.put("lastHeartbeatAt", integration.getLastHeartbeatAt());

		if (Boolean.TRUE.equals(integration.getConnectionLost())) {
			update(service, ServiceMonitoringStatus.UNKNOWN, lostMessage(integration), data);
			return;
		}
		ServiceMonitoringStatus status = highest >= 4
				? ServiceMonitoringStatus.CRITICAL
				: highest >= 2 ? ServiceMonitoringStatus.WARNING : ServiceMonitoringStatus.OK;
		String message = open.isEmpty()
				? "Zabbix (веб-хук): активных проблем нет"
				: "Zabbix (веб-хук): активных проблем %d, максимальная важность %d".formatted(open.size(), highest);
		update(service, status, message, data);
	}


	@Transactional
	public void recomputeIntegration(ZabbixIntegration integration) {
		serviceRepository.findAllByZabbixIntegrationIdAndZabbixEnabledTrueAndActiveTrue(integration.getId())
				.forEach(this::recompute);
	}


	/**
	 * Связь с Zabbix: нет ни событий, ни проверок связи дольше таймаута — статусы сервисов UNKNOWN.
	 */
	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	@Transactional
	public void checkConnections() {
		ZonedDateTime now = ZonedDateTime.now();
		for (ZabbixIntegration integration : integrationRepository.findAllByModeAndActiveTrue(ZabbixIntegrationMode.WEBHOOK)) {
			int timeout = Objects.requireNonNullElse(integration.getHeartbeatTimeoutMinutes(), 15);
			ZonedDateTime lastContact = lastContact(integration);
			boolean lost = timeout > 0 && (lastContact == null || lastContact.isBefore(now.minusMinutes(timeout)));
			if (lost == Boolean.TRUE.equals(integration.getConnectionLost())) {
				continue;
			}
			integration.setConnectionLost(lost);
			integrationRepository.save(integration);
			logRepository.save(MonitoringWebhookLog.builder()
					.integrationId(integration.getId())
					.receivedAt(now)
					.kind(MonitoringWebhookLog.Kind.HEARTBEAT)
					.outcome(lost ? MonitoringWebhookLog.Outcome.CONNECTION_LOST : MonitoringWebhookLog.Outcome.CONNECTION_RESTORED)
					.message(lost ? lostMessage(integration) : "Связь с Zabbix восстановлена")
					.build());
			if (lost) {
				log.warn("Zabbix webhook integration id={} «{}»: no contact since {}", integration.getId(), integration.getName(), lastContact);
				eventPublisher.publish(TriggerType.MONITORING_CONNECTION_LOST, Map.of(
						"integration", new MonitoringProblemEvent.IntegrationRef(integration.getId(), integration.getName()),
						"reason", lostMessage(integration)));
				notifyConnection(integration, UserNotificationEvent.MONITORING_CONNECTION_LOST,
						"Zabbix «%s»: %s. Статус связанных сервисов — «Неизвестно»"
								.formatted(integration.getName(), lowerFirst(lostMessage(integration))));
			} else {
				notifyConnection(integration, UserNotificationEvent.MONITORING_CONNECTION_RESTORED,
						"Связь с Zabbix «%s» восстановлена".formatted(integration.getName()));
			}
			recomputeIntegration(integration);
		}
	}


	@Scheduled(cron = "0 40 3 * * *")
	@Transactional
	public void cleanup() {
		ZonedDateTime before = ZonedDateTime.now().minusDays(RETENTION_DAYS);
		int logs = logRepository.deleteOlderThan(before);
		int problems = problemRepository.deleteResolvedBefore(before);
		if (logs > 0 || problems > 0) {
			log.info("Zabbix webhook cleanup: {} log entries and {} resolved problems older than {} days removed",
					logs, problems, RETENTION_DAYS);
		}
	}


	// ======================================================================
	// Для UI
	// ======================================================================

	@Transactional(readOnly = true)
	public List<Map<String, Object>> log(Long integrationId, int limit) {
		return logRepository.findAllByIntegrationIdOrderByReceivedAtDesc(integrationId, PageRequest.of(0, Math.max(1, Math.min(limit, 500))))
				.stream()
				.map(item -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", item.getId());
					row.put("receivedAt", item.getReceivedAt());
					row.put("kind", item.getKind());
					row.put("eventId", item.getEventId());
					row.put("eventStatus", item.getEventStatus());
					row.put("eventName", item.getEventName());
					row.put("severity", item.getSeverity());
					row.put("host", item.getHost());
					row.put("serviceId", item.getServiceId());
					row.put("serviceCode", item.getServiceCode());
					row.put("outcome", item.getOutcome());
					row.put("message", item.getMessage());
					row.put("remoteIp", item.getRemoteIp());
					return row;
				})
				.toList();
	}


	@Transactional(readOnly = true)
	public Map<String, Object> connectionInfo(ZabbixIntegration integration) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("eventsUrl", eventsUrl(integration));
		result.put("heartbeatUrl", heartbeatUrl(integration));
		result.put("hasWebhookToken", integration.hasWebhookToken());
		result.put("webhookTokenIssuedAt", integration.getWebhookTokenIssuedAt());
		result.put("lastEventAt", integration.getLastEventAt());
		result.put("lastHeartbeatAt", integration.getLastHeartbeatAt());
		result.put("connectionLost", Boolean.TRUE.equals(integration.getConnectionLost()));
		return result;
	}


	/** Итог «проверки» интеграции в режиме веб-хука: когда Zabbix связывался в последний раз. */
	public Map<String, Object> testResult(ZabbixIntegration integration) {
		ZonedDateTime lastContact = lastContact(integration);
		if (!integration.hasWebhookToken()) {
			return Map.of("success", false, "message", "Токен не выпущен: выпустите токен и настройте тип оповещений в Zabbix");
		}
		if (integration.getLastEventAt() == null && integration.getLastHeartbeatAt() == null) {
			return Map.of("success", false, "message", "Zabbix ещё не связывался с ULDesk");
		}
		if (Boolean.TRUE.equals(integration.getConnectionLost())) {
			return Map.of("success", false, "message", lostMessage(integration));
		}
		return Map.of("success", true, "message", "Связь есть, последнее обращение Zabbix: " + format(lastContact));
	}


	// ======================================================================
	// Внутреннее
	// ======================================================================

	private Reply authorize(ZabbixIntegration integration, String token, String remoteIp) {
		if (Boolean.FALSE.equals(integration.getActive()) || !integration.isWebhook()) {
			return Reply.error(404, "Интеграция отключена или не в режиме веб-хука");
		}
		if (token != null && token.trim().matches("^\\{\\$[A-Z0-9_.]+}$")) {
			// Zabbix отправляет неопределённый пользовательский макрос как есть, например {$ULDESK.TOKEN}
			return Reply.error(401, "Zabbix прислал нераскрытый макрос %s вместо токена: создайте его в Zabbix "
					.formatted(token.trim()) + "(Администрирование → Макросы, тип «Секретный текст») со значением токена");
		}
		if (!integration.hasWebhookToken() || token == null || token.isBlank()
				|| !MessageDigest.isEqual(sha256(token.trim()).getBytes(StandardCharsets.US_ASCII),
				integration.getWebhookTokenHash().getBytes(StandardCharsets.US_ASCII))) {
			return Reply.error(401, "Неверный токен в заголовке " + TOKEN_HEADER);
		}
		if (!ipAllowed(integration.getAllowedIps(), remoteIp)) {
			return Reply.error(403, "Адрес " + remoteIp + " не входит в разрешённые для интеграции");
		}
		if (!rateWindows.computeIfAbsent(integration.getId(), id -> new RateWindow()).tryAcquire()) {
			return Reply.error(429, "Слишком много запросов, повторите позже");
		}
		return null;
	}


	private Reply reject(MonitoringWebhookLog entry, Reply reply) {
		entry.setOutcome(MonitoringWebhookLog.Outcome.REJECTED);
		entry.setMessage(truncate("HTTP " + reply.status() + ": " + reply.body().get("message"), 1024));
		logRepository.save(entry);
		return reply;
	}


	private void markContact(ZabbixIntegration integration, ZonedDateTime now, boolean event) {
		if (event) {
			integration.setLastEventAt(now);
		} else {
			integration.setLastHeartbeatAt(now);
		}
		boolean restored = Boolean.TRUE.equals(integration.getConnectionLost());
		integration.setConnectionLost(false);
		integrationRepository.save(integration);
		if (restored) {
			logRepository.save(MonitoringWebhookLog.builder()
					.integrationId(integration.getId())
					.receivedAt(now)
					.kind(MonitoringWebhookLog.Kind.HEARTBEAT)
					.outcome(MonitoringWebhookLog.Outcome.CONNECTION_RESTORED)
					.message("Связь с Zabbix восстановлена")
					.build());
			notifyConnection(integration, UserNotificationEvent.MONITORING_CONNECTION_RESTORED,
					"Связь с Zabbix «%s» восстановлена".formatted(integration.getName()));
			recomputeIntegration(integration);
		}
	}


	/**
	 * Уведомление о связи с Zabbix: владельцам и исполнителям связанных сервисов, ответственным и участникам
	 * их линий; если таких нет — администраторам. Отправка после коммита.
	 */
	private void notifyConnection(ZabbixIntegration integration, UserNotificationEvent event, String message) {
		LinkedHashSet<User> users = new LinkedHashSet<>();
		for (ru.ravel.ItDesk.model.Service service
				: serviceRepository.findAllByZabbixIntegrationIdAndZabbixEnabledTrueAndActiveTrue(integration.getId())) {
			if (service.getOwner() != null) users.add(service.getOwner());
			if (service.getExecutors() != null) users.addAll(service.getExecutors());
			SupportLine line = service.getDefaultSupportLine();
			if (line != null) {
				if (line.getResponsible() != null) users.add(line.getResponsible());
				if (line.getMembers() != null) users.addAll(line.getMembers());
			}
		}
		List<Long> ids = users.stream().filter(User::isEnabled).map(User::getId).filter(Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			ids = userRepository.findAll().stream()
					.filter(User::isEnabled)
					.filter(user -> user.getAuthorities() != null && user.getAuthorities().contains(Role.ADMIN))
					.map(User::getId)
					.filter(Objects::nonNull)
					.toList();
		}
		for (Long userId : ids) {
			afterCommitExecutor.run(() -> userNotificationService.send(new UserNotification(event, message, userId)));
		}
	}


	private static String lowerFirst(String value) {
		return value == null || value.isEmpty() ? "" : Character.toLowerCase(value.charAt(0)) + value.substring(1);
	}


	private void applyEvent(
			MonitoringProblem problem,
			JsonNode body,
			JsonNode event,
			String eventStatus,
			Integer severity,
			List<Map<String, String>> tags,
			ZabbixIntegration integration,
			ZonedDateTime now
	) {
		ZoneId zone = zone(integration);
		JsonNode host = body.path("host");
		problem.setName(truncate(orElse(text(event, "name"), problem.getName()), 1024));
		if (severity != null) {
			problem.setSeverity(severity);
		}
		problem.setSeverityName(truncate(orElse(text(event, "severityName"), problem.getSeverityName()), 64));
		problem.setHost(truncate(orElse(text(host, "host"), problem.getHost()), 255));
		problem.setHostName(truncate(orElse(text(host, "name"), problem.getHostName()), 255));
		problem.setTriggerId(truncate(orElse(text(body.path("trigger"), "id"), problem.getTriggerId()), 64));
		if (event.has("acknowledged")) {
			problem.setAcknowledged(event.get("acknowledged").asBoolean(false));
		}
		problem.setOpdata(truncate(orElse(text(event, "opdata"), problem.getOpdata()), 1024));
		problem.setUrl(truncate(orElse(text(event, "url"), problem.getUrl()), 2048));
		if (!tags.isEmpty()) {
			problem.setTags(tags);
		}
		ZonedDateTime started = date(text(event, "startedAt"), zone);
		if (started != null) {
			problem.setStartedAt(started);
		} else if (problem.getStartedAt() == null) {
			problem.setStartedAt(now);
		}
		if ("RESOLVED".equals(eventStatus)) {
			ZonedDateTime recovered = date(text(event, "recoveredAt"), zone);
			problem.setResolvedAt(recovered == null ? now : recovered);
		}
	}


	/**
	 * Сервис для события. Только среди сервисов этой интеграции: Zabbix клиента не меняет чужие сервисы.
	 * Порядок: тег uldesk_service, Host ID, имя узла, группа узлов.
	 */
	Optional<ru.ravel.ItDesk.model.Service> resolveService(ZabbixIntegration integration, JsonNode body, List<Map<String, String>> tags) {
		List<ru.ravel.ItDesk.model.Service> candidates =
				serviceRepository.findAllByZabbixIntegrationIdAndZabbixEnabledTrueAndActiveTrue(integration.getId());
		if (candidates.isEmpty()) {
			return Optional.empty();
		}
		Set<String> tagged = tags.stream()
				.filter(tag -> SERVICE_TAG.equalsIgnoreCase(tag.get("tag")))
				.map(tag -> normalize(tag.get("value")))
				.filter(value -> !value.isEmpty())
				.collect(Collectors.toSet());
		if (!tagged.isEmpty()) {
			Optional<ru.ravel.ItDesk.model.Service> byTag = candidates.stream()
					.filter(service -> tagged.contains(normalize(service.getCode())) || tagged.contains(normalize(service.getName())))
					.findFirst();
			if (byTag.isPresent()) {
				return byTag;
			}
		}

		JsonNode host = body.path("host");
		String hostId = normalize(text(host, "id"));
		Set<String> hostNames = new HashSet<>(List.of(normalize(text(host, "host")), normalize(text(host, "name"))));
		hostNames.remove("");
		Set<String> groups = split(text(host, "groups"));

		for (ru.ravel.ItDesk.model.Service service : candidates) {
			Map<String, String> ids = Objects.requireNonNullElseGet(service.getMonitoringIdentifiers(), Map::of);
			if (!hostId.isEmpty() && (split(ids.get("hostId")).contains(hostId) || split(ids.get("hostIds")).contains(hostId))) {
				return Optional.of(service);
			}
		}
		for (ru.ravel.ItDesk.model.Service service : candidates) {
			Map<String, String> ids = Objects.requireNonNullElseGet(service.getMonitoringIdentifiers(), Map::of);
			Set<String> names = new HashSet<>(split(ids.get("hostNames")));
			names.addAll(split(ids.get("hosts")));
			if (names.isEmpty() && split(ids.get("hostIds")).isEmpty() && split(ids.get("hostId")).isEmpty()
					&& split(ids.get("hostGroupNames")).isEmpty()) {
				// Как при опросе API: без явных узлов узел называется кодом или названием сервиса
				names.add(normalize(service.getCode()));
				names.add(normalize(service.getName()));
			}
			if (names.stream().anyMatch(hostNames::contains)) {
				return Optional.of(service);
			}
		}
		for (ru.ravel.ItDesk.model.Service service : candidates) {
			Map<String, String> ids = Objects.requireNonNullElseGet(service.getMonitoringIdentifiers(), Map::of);
			if (split(ids.get("hostGroupNames")).stream().anyMatch(groups::contains)) {
				return Optional.of(service);
			}
		}
		return Optional.empty();
	}


	private MonitoringProblemEvent payload(MonitoringProblem problem, JsonNode body, String eventStatus) {
		ru.ravel.ItDesk.model.Service service = problem.getService();
		JsonNode update = body.path("update");
		ZabbixIntegration integration = problem.getIntegration();
		return new MonitoringProblemEvent(
				service == null ? null : new MonitoringProblemEvent.ServiceRef(service.getId(), service.getCode(), service.getName()),
				new MonitoringProblemEvent.Problem(
						problem.getEventId(),
						problem.getStatus().name(),
						eventStatus,
						problem.getName(),
						problem.getSeverity(),
						problem.getSeverityName(),
						problem.getHost(),
						problem.getHostName(),
						text(body.path("host"), "ip"),
						problem.getAcknowledged(),
						problem.getOpdata(),
						problem.getUrl(),
						problem.getStartedAt() == null ? null : problem.getStartedAt().toOffsetDateTime().toString(),
						problem.getResolvedAt() == null ? null : problem.getResolvedAt().toOffsetDateTime().toString(),
						problem.getTags(),
						text(update, "action"),
						text(update, "message"),
						text(update, "user")
				),
				new MonitoringProblemEvent.IntegrationRef(integration.getId(), integration.getName())
		);
	}


	private Map<String, Object> response(
			ZabbixIntegration integration,
			MonitoringProblem problem,
			ru.ravel.ItDesk.model.Service service,
			MonitoringWebhookLog.Outcome outcome
	) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("accepted", true);
		result.put("eventId", problem.getEventId());
		result.put("outcome", outcome.name());
		Map<String, String> tags = new LinkedHashMap<>();
		String base = blankToNull(integration.getPublicUrl());
		if (service != null) {
			result.put("service", Map.of("code", service.getCode(), "status", service.getMonitoringStatus().name()));
			Optional<ru.ravel.ItDesk.model.incident.SupportIncident> incident =
					incidentRepository.findFirstByServicesIdAndStatusNotInOrderByIdDesc(service.getId(), INACTIVE_INCIDENT);
			incident.ifPresent(item -> result.put("incidentId", item.getId()));
			if (base != null) {
				if (incident.isPresent()) {
					String number = Objects.toString(incident.get().getIncidentNumber(), "#" + incident.get().getId());
					tags.put("__uldesk_ref", "Инцидент " + number);
					tags.put("__uldesk_url", trimSlash(base) + "/incidents/" + incident.get().getId());
				} else {
					tags.put("__uldesk_ref", "Сервис " + service.getCode());
					tags.put("__uldesk_url", trimSlash(base) + "/settings/services");
				}
			}
		}
		// Zabbix добавит эти теги к событию (Process tags), а меню события откроет ссылку в ULDesk
		result.put("tags", tags);
		return result;
	}


	private Map<String, Object> problemRow(MonitoringProblem problem) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put("eventId", problem.getEventId());
		row.put("name", problem.getName());
		row.put("severity", problem.getSeverity());
		row.put("clock", problem.getStartedAt() == null ? null : String.valueOf(problem.getStartedAt().toEpochSecond()));
		row.put("startedAt", problem.getStartedAt());
		row.put("acknowledged", Boolean.TRUE.equals(problem.getAcknowledged()));
		row.put("host", problem.getHostName() == null ? problem.getHost() : problem.getHostName());
		row.put("url", problem.getUrl());
		return row;
	}


	private void update(ru.ravel.ItDesk.model.Service service, ServiceMonitoringStatus status, String message, Map<String, Object> data) {
		boolean statusChanged = service.getMonitoringStatus() != status;
		service.setMonitoringStatus(status);
		service.setLastMonitoringAt(ZonedDateTime.now());
		service.setLastMonitoringMessage(truncate(message, 2000));
		service.setLastMonitoringData(new LinkedHashMap<>(data));
		serviceRepository.save(service);
		// В поисковом документе сервиса из мониторинга есть только статус: ежеминутная проверка без изменений его не трогает
		if (statusChanged) {
			globalSearchService.indexService(service);
		}
	}


	private ZonedDateTime lastContact(ZabbixIntegration integration) {
		return Stream.of(integration.getLastEventAt(), integration.getLastHeartbeatAt(), integration.getWebhookTokenIssuedAt())
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder())
				.orElse(null);
	}


	private String lostMessage(ZabbixIntegration integration) {
		ZonedDateTime last = integration.getLastEventAt() == null && integration.getLastHeartbeatAt() == null
				? null
				: Stream.of(integration.getLastEventAt(), integration.getLastHeartbeatAt()).filter(Objects::nonNull)
				.max(Comparator.naturalOrder()).orElse(null);
		return last == null
				? "Нет связи с Zabbix: он ещё не обращался к ULDesk"
				: "Нет связи с Zabbix с " + format(last) + ": нет ни событий, ни проверок связи";
	}


	private Integer severity(JsonNode event) {
		JsonNode value = event.path("severity");
		if (value.isNumber()) {
			return Math.max(0, Math.min(5, value.asInt()));
		}
		if (value.isTextual() && value.asText().trim().matches("\\d")) {
			return Math.max(0, Math.min(5, Integer.parseInt(value.asText().trim())));
		}
		return null;
	}


	private List<Map<String, String>> tags(JsonNode node) {
		if (node == null || !node.isArray()) {
			return List.of();
		}
		List<Map<String, String>> result = new ArrayList<>();
		for (JsonNode item : node) {
			String tag = text(item, "tag");
			if (tag != null && result.size() < 50) {
				result.add(Map.of("tag", truncate(tag, 255), "value", truncate(Objects.toString(text(item, "value"), ""), 255)));
			}
		}
		return result;
	}


	private ZoneId zone(ZabbixIntegration integration) {
		try {
			return integration.getEventTimezone() == null || integration.getEventTimezone().isBlank()
					? ZoneId.systemDefault()
					: ZoneId.of(integration.getEventTimezone().trim());
		} catch (Exception e) {
			return ZoneId.systemDefault();
		}
	}


	/** Дата Zabbix «2026.10.17 12:00:01» в часовом поясе интеграции или ISO-8601 со смещением. */
	static ZonedDateTime date(String value, ZoneId zone) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return java.time.LocalDateTime.parse(value.trim(), ZABBIX_DATE_TIME).atZone(zone);
		} catch (Exception ignored) {
		}
		try {
			return OffsetDateTime.parse(value.trim()).toZonedDateTime();
		} catch (Exception ignored) {
			return null;
		}
	}


	/** Разрешённые адреса: точные адреса и подсети CIDR через запятую или пробел. Пусто — любой адрес. */
	static boolean ipAllowed(String allowed, String remoteIp) {
		if (allowed == null || allowed.isBlank()) {
			return true;
		}
		if (remoteIp == null || remoteIp.isBlank()) {
			return false;
		}
		try {
			byte[] remote = InetAddress.getByName(remoteIp.trim()).getAddress();
			for (String rule : allowed.split("[,;\\s]+")) {
				if (rule.isBlank()) continue;
				String[] parts = rule.trim().split("/", 2);
				byte[] network = InetAddress.getByName(parts[0]).getAddress();
				if (network.length != remote.length) continue;
				int bits = parts.length == 2 ? Integer.parseInt(parts[1]) : network.length * 8;
				if (matches(remote, network, bits)) {
					return true;
				}
			}
		} catch (Exception e) {
			log.warn("Invalid allowed IP list for Zabbix webhook: {}", e.getMessage());
		}
		return false;
	}


	private static boolean matches(byte[] address, byte[] network, int bits) {
		int full = bits / 8;
		for (int index = 0; index < full && index < address.length; index++) {
			if (address[index] != network[index]) return false;
		}
		int rest = bits % 8;
		if (rest == 0 || full >= address.length) return true;
		int mask = 0xFF << (8 - rest) & 0xFF;
		return (address[full] & mask) == (network[full] & mask);
	}


	private String baseUrl(ZabbixIntegration integration) {
		String base = blankToNull(integration.getPublicUrl());
		return base == null ? "" : trimSlash(base);
	}


	private String randomToken(int bytes) {
		byte[] buffer = new byte[bytes];
		random.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}


	static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}


	private static String text(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.isValueNode() ? value.asText() : value.toString();
		// Zabbix оставляет нераскрытые макросы как есть: «{EVENT.RECOVERY.DATE}»
		return text.isBlank() || text.matches("^\\{[A-Z0-9_.$#]+}$") ? null : text.trim();
	}


	/** Новое значение, а если его нет — прежнее (оба могут быть null). */
	private static String orElse(String value, String previous) {
		return value != null ? value : previous;
	}


	private static Set<String> split(String value) {
		if (value == null || value.isBlank()) {
			return Set.of();
		}
		return Arrays.stream(value.split("[,;]+"))
				.map(ZabbixWebhookService::normalize)
				.filter(item -> !item.isEmpty())
				.collect(Collectors.toSet());
	}


	private static String normalize(String value) {
		return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
	}


	private static String format(ZonedDateTime value) {
		return value == null ? "—" : value.withZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
	}


	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}


	private static String trimSlash(String value) {
		return value.replaceAll("/+$", "");
	}


	private static String truncate(String value, int length) {
		return value == null || value.length() <= length ? value : value.substring(0, length);
	}


	/** Простое ограничение частоты: не больше MAX_REQUESTS_PER_MINUTE запросов в минуту на интеграцию. */
	private static final class RateWindow {
		private long windowStart = System.currentTimeMillis();
		private int count;

		synchronized boolean tryAcquire() {
			long now = System.currentTimeMillis();
			if (now - windowStart >= Duration.ofMinutes(1).toMillis()) {
				windowStart = now;
				count = 0;
			}
			return ++count <= MAX_REQUESTS_PER_MINUTE;
		}
	}

}

package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;
import ru.ravel.ItDesk.dto.MonitoringProblemEvent;
import ru.ravel.ItDesk.model.MonitoringProblem;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import ru.ravel.ItDesk.model.MonitoringWebhookLog;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;
import ru.ravel.ItDesk.model.ZabbixIntegration;
import ru.ravel.ItDesk.model.ZabbixIntegrationMode;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.MonitoringProblemRepository;
import ru.ravel.ItDesk.repository.MonitoringWebhookLogRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.UserRepository;
import ru.ravel.ItDesk.repository.ZabbixIntegrationRepository;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ZabbixWebhookServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final List<MonitoringProblem> problems = new ArrayList<>();
	private final List<MonitoringWebhookLog> logs = new ArrayList<>();

	private ZabbixIntegrationRepository integrationRepository;
	private ServiceRepository serviceRepository;
	private EventPublisher eventPublisher;
	private UserNotificationService userNotificationService;
	private GlobalSearchService globalSearchService;
	private ZabbixWebhookService service;
	private ZabbixIntegration integration;
	private ru.ravel.ItDesk.model.Service oneC;
	private ru.ravel.ItDesk.model.Service internet;
	private String token;

	@BeforeEach
	void setUp() {
		integrationRepository = mock(ZabbixIntegrationRepository.class);
		MonitoringProblemRepository problemRepository = mock(MonitoringProblemRepository.class);
		MonitoringWebhookLogRepository logRepository = mock(MonitoringWebhookLogRepository.class);
		serviceRepository = mock(ServiceRepository.class);
		IncidentRepository incidentRepository = mock(IncidentRepository.class);
		eventPublisher = mock(EventPublisher.class);
		userNotificationService = mock(UserNotificationService.class);
		UserRepository userRepository = mock(UserRepository.class);
		globalSearchService = mock(GlobalSearchService.class);
		when(userRepository.findAll()).thenReturn(List.of(
				User.builder().id(1L).username("admin").authorities(List.of(Role.ADMIN)).build(),
				User.builder().id(2L).username("operator").authorities(List.of(Role.OPERATOR)).build()));
		service = new ZabbixWebhookService(integrationRepository, problemRepository, logRepository, serviceRepository,
				incidentRepository, eventPublisher, globalSearchService, userNotificationService, userRepository,
				new ru.ravel.ItDesk.component.AfterCommitExecutor(mock(org.springframework.transaction.PlatformTransactionManager.class)));

		integration = ZabbixIntegration.builder()
				.id(7L).name("Клиент А").mode(ZabbixIntegrationMode.WEBHOOK).active(true)
				.publicUrl("https://uldesk.example").eventTimezone("Europe/Moscow").heartbeatTimeoutMinutes(15)
				.build();
		oneC = ru.ravel.ItDesk.model.Service.builder().id(1L).code("1C").name("1С").zabbixEnabled(true)
				.zabbixIntegration(integration).monitoringStatus(ServiceMonitoringStatus.NOT_CONFIGURED)
				.monitoringIdentifiers(new LinkedHashMap<>()).build();
		internet = ru.ravel.ItDesk.model.Service.builder().id(2L).code("INT").name("Интернет").zabbixEnabled(true)
				.zabbixIntegration(integration).monitoringStatus(ServiceMonitoringStatus.NOT_CONFIGURED)
				.monitoringIdentifiers(new LinkedHashMap<>(Map.of("hostNames", "gw-01, gw-02"))).build();

		when(integrationRepository.findById(7L)).thenReturn(Optional.of(integration));
		when(integrationRepository.findByWebhookKey(any())).thenAnswer(inv ->
				Objects.equals(inv.getArgument(0), integration.getWebhookKey()) ? Optional.of(integration) : Optional.empty());
		when(integrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(integrationRepository.findAllByModeAndActiveTrue(ZabbixIntegrationMode.WEBHOOK)).thenReturn(List.of(integration));
		when(serviceRepository.findAllByZabbixIntegrationIdAndZabbixEnabledTrueAndActiveTrue(7L)).thenReturn(List.of(oneC, internet));
		when(serviceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(incidentRepository.findFirstByServicesIdAndStatusNotInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.empty());

		when(problemRepository.findByIntegrationIdAndEventId(eq(7L), any())).thenAnswer(inv -> problems.stream()
				.filter(problem -> problem.getEventId().equals(inv.getArgument(1))).findFirst());
		when(problemRepository.save(any())).thenAnswer(inv -> {
			MonitoringProblem problem = inv.getArgument(0);
			if (problems.stream().noneMatch(item -> item == problem)) problems.add(problem);
			return problem;
		});
		when(problemRepository.findAllByServiceIdAndStatusOrderBySeverityDescStartedAtDesc(anyLong(), eq(MonitoringProblem.Status.OPEN)))
				.thenAnswer(inv -> problems.stream()
						.filter(problem -> problem.getService() != null && problem.getService().getId().equals(inv.getArgument(0)))
						.filter(problem -> problem.getStatus() == MonitoringProblem.Status.OPEN)
						.toList());
		when(logRepository.save(any())).thenAnswer(inv -> {
			logs.add(inv.getArgument(0));
			return inv.getArgument(0);
		});

		token = service.issueToken(7L).token();
	}

	@Test
	void problemByServiceTagMakesServiceCriticalAndPublishesEvent() throws Exception {
		ZabbixWebhookService.Reply reply = service.receiveEvent(integration.getWebhookKey(), token, "10.0.0.5",
				event("48211", "PROBLEM", 4, "[{\"tag\":\"uldesk_service\",\"value\":\"1c\"}]", "srv-1c-01"));

		assertEquals(200, reply.status());
		assertEquals("ACCEPTED", reply.body().get("outcome"));
		assertEquals(ServiceMonitoringStatus.CRITICAL, oneC.getMonitoringStatus());
		@SuppressWarnings("unchecked")
		Map<String, String> tags = (Map<String, String>) reply.body().get("tags");
		assertEquals("https://uldesk.example/settings/services", tags.get("__uldesk_url"));
		assertEquals(ZonedDateTime.of(2026, 10, 17, 12, 0, 1, 0, ZoneId.of("Europe/Moscow")), problems.getFirst().getStartedAt());
		verify(eventPublisher).publish(eq(TriggerType.MONITORING_PROBLEM_STARTED), argThat(payload ->
				payload instanceof MonitoringProblemEvent event && "1C".equals(event.service().code())
						&& event.problem().severity() == 4 && "PROBLEM".equals(event.problem().eventStatus())));
		assertNotNull(integration.getLastEventAt());
	}

	@Test
	void resolvedClosesProblemAndRepeatIsDuplicate() throws Exception {
		service.receiveEvent(integration.getWebhookKey(), token, null, event("1", "PROBLEM", 2, "[]", "gw-01"));
		assertEquals(ServiceMonitoringStatus.WARNING, internet.getMonitoringStatus());

		ZabbixWebhookService.Reply resolved = service.receiveEvent(integration.getWebhookKey(), token, null, event("1", "RESOLVED", 2, "[]", "gw-01"));
		assertEquals("ACCEPTED", resolved.body().get("outcome"));
		assertEquals(ServiceMonitoringStatus.OK, internet.getMonitoringStatus());
		assertEquals(MonitoringProblem.Status.RESOLVED, problems.getFirst().getStatus());

		ZabbixWebhookService.Reply repeat = service.receiveEvent(integration.getWebhookKey(), token, null, event("1", "RESOLVED", 2, "[]", "gw-01"));
		assertEquals("DUPLICATE", repeat.body().get("outcome"));
		// Запоздавший PROBLEM после восстановления не открывает проблему снова
		service.receiveEvent(integration.getWebhookKey(), token, null, event("1", "PROBLEM", 2, "[]", "gw-01"));
		assertEquals(ServiceMonitoringStatus.OK, internet.getMonitoringStatus());
		verify(eventPublisher, times(1)).publish(eq(TriggerType.MONITORING_PROBLEM_RESOLVED), any());
		verify(eventPublisher, times(1)).publish(eq(TriggerType.MONITORING_PROBLEM_STARTED), any());
	}

	@Test
	void unknownHostIsAcceptedButUnmapped() throws Exception {
		ZabbixWebhookService.Reply reply = service.receiveEvent(integration.getWebhookKey(), token, null, event("5", "PROBLEM", 5, "[]", "printer-7"));
		assertEquals(200, reply.status());
		assertEquals("UNMAPPED", reply.body().get("outcome"));
		assertNull(problems.getFirst().getService());
		assertEquals(MonitoringWebhookLog.Outcome.UNMAPPED, logs.getLast().getOutcome());
	}

	@Test
	void rejectsWrongTokenIpAndContract() throws Exception {
		JsonNode body = event("9", "PROBLEM", 4, "[]", "gw-01");
		assertEquals(401, service.receiveEvent(integration.getWebhookKey(), "wrong", null, body).status());
		ZabbixWebhookService.Reply macro = service.heartbeat(integration.getWebhookKey(), "{$ULDESK.TOKEN}", null);
		assertEquals(401, macro.status());
		assertTrue(macro.body().get("message").toString().contains("нераскрытый макрос {$ULDESK.TOKEN}"));
		assertEquals(404, service.receiveEvent("unknown-key", token, null, body).status());

		integration.setAllowedIps("192.168.10.0/24, 10.1.1.1");
		assertEquals(403, service.receiveEvent(integration.getWebhookKey(), token, "10.0.0.5", body).status());
		assertEquals(200, service.receiveEvent(integration.getWebhookKey(), token, "192.168.10.77", body).status());
		integration.setAllowedIps(null);

		assertEquals(400, service.receiveEvent(integration.getWebhookKey(), token, null,
				mapper.readTree("{\"schema\":\"uldesk.zabbix.v1\",\"event\":{\"id\":\"1\",\"status\":\"OPEN\"}}")).status());
		assertEquals(400, service.receiveEvent(integration.getWebhookKey(), token, null,
				mapper.readTree("{\"schema\":\"other.v2\",\"event\":{\"id\":\"1\",\"status\":\"PROBLEM\"}}")).status());
		assertTrue(logs.stream().filter(entry -> entry.getOutcome() == MonitoringWebhookLog.Outcome.REJECTED).count() >= 4);
		assertTrue(problems.stream().allMatch(problem -> problem.getEventId().equals("9")));
	}

	@Test
	void lostConnectionMakesStatusUnknownUntilZabbixIsBack() throws Exception {
		service.receiveEvent(integration.getWebhookKey(), token, null, event("1", "PROBLEM", 4, "[]", "gw-01"));
		integration.setLastEventAt(ZonedDateTime.now().minusMinutes(40));
		integration.setWebhookTokenIssuedAt(ZonedDateTime.now().minusHours(2));

		service.checkConnections();
		assertTrue(integration.getConnectionLost());
		assertEquals(MonitoringWebhookLog.Outcome.CONNECTION_LOST, logs.getLast().getOutcome());
		assertEquals(ServiceMonitoringStatus.UNKNOWN, internet.getMonitoringStatus());
		verify(eventPublisher).publish(eq(TriggerType.MONITORING_CONNECTION_LOST), any());
		// у сервисов нет ответственных — уведомление получает администратор
		ArgumentCaptor<UserNotification> lostNotice = ArgumentCaptor.forClass(UserNotification.class);
		verify(userNotificationService).send(lostNotice.capture());
		assertEquals(UserNotificationEvent.MONITORING_CONNECTION_LOST, lostNotice.getValue().getEvent());
		assertEquals(1L, lostNotice.getValue().getUserId());
		assertTrue(lostNotice.getValue().getMessage().startsWith("Zabbix «Клиент А»: нет связи с Zabbix"), lostNotice.getValue().getMessage());

		assertEquals(200, service.heartbeat(integration.getWebhookKey(), token, null).status());
		assertFalse(integration.getConnectionLost());
		assertEquals(MonitoringWebhookLog.Outcome.CONNECTION_RESTORED, logs.getLast().getOutcome());
		verify(userNotificationService).send(argThat(notice -> notice.getEvent() == UserNotificationEvent.MONITORING_CONNECTION_RESTORED));
		assertEquals(ServiceMonitoringStatus.CRITICAL, internet.getMonitoringStatus());
	}

	@Test
	void periodicCheckReindexesServiceOnlyWhenStatusChanges() throws Exception {
		service.recompute(oneC);
		service.recompute(oneC);
		service.recompute(oneC);
		verify(globalSearchService, times(1)).indexService(oneC);

		service.receiveEvent(integration.getWebhookKey(), token, "10.0.0.5",
				event("48211", "PROBLEM", 4, "[{\"tag\":\"uldesk_service\",\"value\":\"1c\"}]", "srv-1c-01"));
		service.recompute(oneC);
		verify(globalSearchService, times(2)).indexService(oneC);
	}

	@Test
	void parsesZabbixDatesAndIpRules() {
		assertEquals(ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneId.of("UTC")),
				ZabbixWebhookService.date("2026.01.02 03:04:05", ZoneId.of("UTC")));
		assertNotNull(ZabbixWebhookService.date("2026-01-02T03:04:05+03:00", ZoneId.of("UTC")));
		assertNull(ZabbixWebhookService.date("{EVENT.RECOVERY.DATE} {EVENT.RECOVERY.TIME}", ZoneId.of("UTC")));
		assertTrue(ZabbixWebhookService.ipAllowed("", "1.2.3.4"));
		assertTrue(ZabbixWebhookService.ipAllowed("10.0.0.0/8", "10.200.1.1"));
		assertFalse(ZabbixWebhookService.ipAllowed("10.0.0.0/8", "11.0.0.1"));
		assertTrue(ZabbixWebhookService.ipAllowed("2001:db8::/32", "2001:db8::1"));
	}

	@Test
	@SuppressWarnings("unchecked")
	void mediaTypeYamlIsValidAndCarriesContract() {
		integration.setBaseUrl("https://zabbix.client.local/api_jsonrpc.php");
		String yaml = new ZabbixMediaTypeGenerator(service).yaml(integration, token);

		Map<String, Object> root = new Yaml().load(yaml);
		Map<String, Object> export = (Map<String, Object>) root.get("zabbix_export");
		Map<String, Object> mediaType = ((List<Map<String, Object>>) export.get("media_types")).getFirst();
		assertEquals("WEBHOOK", mediaType.get("type"));
		// Теги, которые принимает импорт Zabbix 6.0 (ui/include/classes/import/validators/C60XmlValidator.php)
		Set<String> allowed = Set.of("name", "type", "parameters", "status", "max_sessions", "attempts", "attempt_interval",
				"script", "timeout", "process_tags", "show_event_menu", "event_menu_url", "event_menu_name", "description",
				"message_templates");
		assertTrue(allowed.containsAll(mediaType.keySet()), "неизвестные Zabbix теги: " + mediaType.keySet());
		Map<String, String> parameters = new LinkedHashMap<>();
		((List<Map<String, String>>) mediaType.get("parameters")).forEach(item -> parameters.put(item.get("name"), item.get("value")));
		assertEquals("https://uldesk.example/api/v1/integrations/zabbix/" + integration.getWebhookKey() + "/events", parameters.get("uldesk_url"));
		assertEquals(token, parameters.get("uldesk_token"));
		assertEquals("https://zabbix.client.local", parameters.get("zabbix_url"));
		assertEquals("{EVENT.TAGSJSON}", parameters.get("event_tags"));
		assertTrue(((String) mediaType.get("script")).contains("X-ULDesk-Token"));
		assertTrue(((String) mediaType.get("script")).contains("uldesk.zabbix.v1"));
		assertEquals(3, ((List<?>) mediaType.get("message_templates")).size());

		assertTrue(new ZabbixMediaTypeGenerator(service).yaml(integration, null).contains(ZabbixMediaTypeGenerator.TOKEN_PLACEHOLDER));
	}

	private JsonNode event(String id, String status, int severity, String tagsJson, String host) throws Exception {
		return mapper.readTree("""
				{
				  "schema": "uldesk.zabbix.v1",
				  "event": {
				    "id": "%s", "status": "%s", "name": "Проблема %s", "severity": %d, "severityName": "High",
				    "startedAt": "2026.10.17 12:00:01", "recoveredAt": "{EVENT.RECOVERY.DATE} {EVENT.RECOVERY.TIME}",
				    "acknowledged": false, "tags": %s
				  },
				  "trigger": {"id": "23011"},
				  "host": {"id": "10501", "host": "%s", "name": "%s", "ip": "10.10.0.15"}
				}""".formatted(id, status, id, severity, tagsJson, host, host));
	}
}

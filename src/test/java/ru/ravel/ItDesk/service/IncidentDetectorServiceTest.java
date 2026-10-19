package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import db.migration.V62__jev_incident_detector_rule;
import db.migration.V62__jev_incident_detector_rule.Params;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ravel.ItDesk.dto.AutomationExecutionContext;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.AutomationTrigger;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Event;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.IncidentSignal;
import ru.ravel.ItDesk.model.incident.IncidentSignalDecision;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.repository.*;

import java.lang.reflect.Constructor;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Правило «Детектор массовых инцидентов (Jev)» прогоняется целиком: его граф из {@link V62__jev_incident_detector_rule}
 * проходится так же, как движок автоматизаций (CONDITION — {@link AutomationScriptRuntime#evaluateExpression},
 * ACTION — {@link AutomationScriptRuntime#executeActions}, SET_VARIABLE), а шаги — настоящие
 * {@link AutomationActionExecutor} и {@link IncidentDetectorService} поверх замоканных репозиториев и Jev.
 * Так проверяется и логика шагов, и то, что решение действительно живёт в нодах правила (числа и условия
 * можно менять, не трогая код).
 */
class IncidentDetectorServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private JevService jevService;
	private IncidentSignalRepository signalRepository;
	private IncidentRepository incidentRepository;
	private TaskRepository taskRepository;
	private IncidentService incidentService;
	private UserNotificationService userNotificationService;
	private AutomationScriptRuntime runtime;
	private AutomationWorkflowValueService values;
	private final List<IncidentSignal> stored = new ArrayList<>();
	private final Map<Long, SupportIncident> incidents = new HashMap<>();
	private final Map<Long, Long> organizationOfClient = new HashMap<>();
	private final ru.ravel.ItDesk.model.Service vpn = ru.ravel.ItDesk.model.Service.builder()
			.id(1L).code("vpn").name("VPN").build();
	private Params params = Params.defaults();
	private UnaryOperator<String> ruleEdit = UnaryOperator.identity();

	@BeforeEach
	void setUp() throws Exception {
		jevService = mock(JevService.class);
		signalRepository = mock(IncidentSignalRepository.class);
		incidentRepository = mock(IncidentRepository.class);
		taskRepository = mock(TaskRepository.class);
		incidentService = mock(IncidentService.class);
		ClientRepository clientRepository = mock(ClientRepository.class);
		MessageRepository messageRepository = mock(MessageRepository.class);
		ServiceRepository serviceRepository = mock(ServiceRepository.class);
		AppSettingsService appSettingsService = mock(AppSettingsService.class);
		userNotificationService = mock(UserNotificationService.class);
		UserRepository userRepository = mock(UserRepository.class);
		// если у сервиса и линии нет ответственных — уведомляется администратор
		when(userRepository.findAll()).thenReturn(List.of(
				User.builder().id(1L).username("admin").authorities(List.of(Role.ADMIN)).build(),
				User.builder().id(2L).username("operator").authorities(List.of(Role.OPERATOR)).build()));
		IncidentDetectorService detector = new IncidentDetectorService(jevService, signalRepository, incidentRepository,
				clientRepository, messageRepository, taskRepository, serviceRepository, incidentService,
				appSettingsService, userNotificationService, userRepository, mock(PlatformTransactionManager.class),
				mock(JevDecisionRecorder.class));
		values = new AutomationWorkflowValueService(mapper);
		AutomationActionExecutor executor = executor(Map.of(
				JevService.class, jevService,
				IncidentDetectorService.class, detector,
				AutomationWorkflowValueService.class, values));
		runtime = new AutomationScriptRuntime(mapper, executor, appSettingsService);

		when(jevService.isEnabled()).thenReturn(true);
		when(appSettingsService.getGeneralSettings()).thenReturn(new AppSettings());
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(vpn));
		when(clientRepository.findById(anyLong())).thenAnswer(inv -> {
			Long id = inv.getArgument(0);
			Long organizationId = organizationOfClient.get(id);
			return Optional.of(Client.builder().id(id)
					.organization(organizationId == null ? null : Organization.builder().id(organizationId).name("Орг " + organizationId).build())
					.build());
		});
		when(clientRepository.findAllById(any())).thenReturn(List.of());
		when(incidentRepository.findAllByStatusNotIn(any())).thenReturn(List.of());
		when(incidentRepository.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(incidents.get(inv.<Long>getArgument(0))));
		when(messageRepository.findAllById(any())).thenReturn(List.of());
		// у каждого клиента своя заявка: id заявки = 100 + id клиента
		when(taskRepository.findAllByClientIdAndLinkedMessageId(anyLong(), anyLong())).thenReturn(List.of());
		when(taskRepository.findAllByMessageId(anyLong())).thenReturn(List.of());
		when(taskRepository.findOpenByClientIdCreatedSince(anyLong(), any())).thenAnswer(inv ->
				List.of(Task.builder().id(100L + inv.<Long>getArgument(0)).build()));
		when(incidentService.linkTasksForSystem(anyLong(), any())).thenAnswer(inv -> new ArrayList<>(inv.<Collection<Long>>getArgument(1)));
		when(incidentService.createDraftForSystem(any())).thenAnswer(inv -> {
			IncidentService.SystemIncidentDraft draft = inv.getArgument(0);
			SupportIncident incident = SupportIncident.builder().id(77L).incidentNumber("INC-000077")
					.title(draft.title()).status(IncidentStatus.DRAFT).severity(draft.severity()).build();
			incidents.put(77L, incident);
			return incident;
		});
		when(signalRepository.save(any())).thenAnswer(inv -> {
			IncidentSignal signal = inv.getArgument(0);
			// equals у сущности сравнивает id: сигналу без id выдаём его при первом сохранении
			if (signal.getId() == null) {
				signal.setId(stored.size() + 1L);
				stored.add(signal);
			}
			return signal;
		});
		when(signalRepository.findById(anyLong())).thenAnswer(inv -> stored.stream()
				.filter(s -> s.getId().equals(inv.getArgument(0))).findFirst());
		when(signalRepository.findAllById(any())).thenAnswer(inv -> {
			Collection<Long> ids = inv.getArgument(0);
			return stored.stream().filter(s -> ids.contains(s.getId())).toList();
		});
		// окно: всё, что уже сохранено как сбой по тому же сервису и симптому
		when(signalRepository.findWindow(any(), any(), any(), any())).thenAnswer(inv -> stored.stream()
				.filter(s -> s.getServiceId() != null && s.getServiceId().equals(inv.getArgument(0)))
				.filter(s -> s.getSymptom().equals(inv.getArgument(1)))
				.filter(s -> inv.<Collection<IncidentSignalDecision>>getArgument(3).contains(s.getDecision()))
				.toList());
		when(signalRepository.existsByServiceIdAndSymptomAndDecisionAndCreatedAtAfter(any(), any(), any(), any()))
				.thenAnswer(inv -> stored.stream().anyMatch(s -> s.getDecision() == IncidentSignalDecision.TRIGGERED));
		when(signalRepository.existsByServiceIdAndSymptomAndDecisionAndMatchedIncidentIdIsNotNullAndCreatedAtAfter(any(), any(), any(), any()))
				.thenAnswer(inv -> stored.stream().anyMatch(s -> s.getDecision() == IncidentSignalDecision.TRIGGERED
						&& s.getMatchedIncidentId() != null));
		when(signalRepository.findFirstByServiceIdAndSymptomAndDecisionAndMatchedIncidentIdIsNotNullAndCreatedAtAfterOrderByCreatedAtDesc(
				any(), any(), any(), any()))
				.thenAnswer(inv -> stored.stream()
						.filter(s -> s.getDecision() == IncidentSignalDecision.TRIGGERED && s.getMatchedIncidentId() != null)
						.reduce((first, second) -> second));
		verdict(0.9, IncidentSeverity.P2);
	}

	@Test
	void notAnOutageIsIgnored() throws Exception {
		outage(0.3, vpn, null);
		assertEquals(IncidentSignalDecision.IGNORED, handle(1L, 10L));
	}

	@Test
	void outageWithoutServiceIsIgnored() throws Exception {
		outage(0.95, null, null);
		assertEquals(IncidentSignalDecision.IGNORED, handle(1L, 10L));
	}

	@Test
	void notifyOnlyTriggersOnThirdDistinctClientThenCooldown() throws Exception {
		outage(0.9, vpn, null);
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 10L));
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 11L)); // тот же клиент повторно — всё ещё 1 клиент
		assertEquals(IncidentSignalDecision.COUNTED, handle(2L, 12L));
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 13L));
		assertEquals(IncidentSignalDecision.COOLDOWN, handle(4L, 14L));
		verify(jevService, times(1)).verifyBatch(anyString(), anyList());
		verifyNoInteractions(incidentService);
		// без владельца сервиса и линии уведомление получает только администратор
		ArgumentCaptor<UserNotification> notification = ArgumentCaptor.forClass(UserNotification.class);
		verify(userNotificationService, times(1)).send(notification.capture());
		assertEquals(1L, notification.getValue().getUserId());
		assertEquals(UserNotificationEvent.INCIDENT_DETECTED, notification.getValue().getEvent());
		assertTrue(notification.getValue().getMessage().startsWith("Обнаружен массовый сбой. Массовые обращения: VPN — недоступен"),
				notification.getValue().getMessage());
		assertTrue(notification.getValue().getMessage().contains("Инцидент не создан"));
	}

	@Test
	void batchRejectedWhenMessagesDiffer() throws Exception {
		outage(0.9, vpn, null);
		verdict(0.3, IncidentSeverity.P3);
		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.BATCH_REJECTED, handle(3L, 12L));
	}

	@Test
	void unavailableBatchVerificationOnlyCounts() throws Exception {
		outage(0.9, vpn, null);
		when(jevService.verifyBatch(anyString(), anyList())).thenReturn(Optional.empty());
		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.COUNTED, handle(3L, 12L));
		verifyNoInteractions(userNotificationService);
	}

	@Test
	void monitoringAlertLowersThreshold() throws Exception {
		vpn.setMonitoringStatus(ServiceMonitoringStatus.CRITICAL);
		outage(0.9, vpn, null);
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 10L));
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(2L, 11L));
	}

	@Test
	void unavailableMonitoringDoesNotLowerThreshold() throws Exception {
		// UNAVAILABLE — недоступен сам Zabbix или интеграция, а не сервис: порог прежний
		vpn.setMonitoringStatus(ServiceMonitoringStatus.UNAVAILABLE);
		outage(0.9, vpn, null);
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 10L));
		assertEquals(IncidentSignalDecision.COUNTED, handle(2L, 11L));
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 12L));
	}

	@Test
	void notifyOnlyMatchedIncidentDoesNotLink() throws Exception {
		SupportIncident incident = SupportIncident.builder().id(7L).incidentNumber("INC-000007").title("VPN недоступен").build();
		outage(0.9, vpn, incident);
		assertEquals(IncidentSignalDecision.MATCHED_INCIDENT, handle(1L, 10L));
		assertEquals(7L, stored.getFirst().getMatchedIncidentId());
		verifyNoInteractions(incidentService);
	}

	@Test
	void createDraftFillsFieldsAndLinksTasks() throws Exception {
		params = new Params(15, 3, 0.7, 60, true, 10, 0.6);
		User owner = User.builder().id(5L).username("vpn-owner").build();
		User executor = User.builder().id(6L).username("engineer").build();
		vpn.setOwner(owner);
		vpn.setExecutors(new LinkedHashSet<>(List.of(owner, executor)));
		vpn.setDefaultSupportLine(SupportLine.builder().id(3L).name("Сеть").build());
		outage(0.9, vpn, null);
		verdict(0.95, IncidentSeverity.P1);

		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 12L));

		ArgumentCaptor<IncidentService.SystemIncidentDraft> captor = ArgumentCaptor.forClass(IncidentService.SystemIncidentDraft.class);
		verify(incidentService).createDraftForSystem(captor.capture());
		IncidentService.SystemIncidentDraft draft = captor.getValue();
		assertEquals("Массовые обращения: VPN — недоступен", draft.title());
		assertEquals(IncidentSeverity.P2, draft.severity(), "P1 only with >= 10 clients");
		assertTrue(draft.description().contains("снижена до P2"), draft.description());
		assertTrue(draft.description().contains("Вероятная причина (оценка Jev): сеть"), draft.description());
		assertTrue(draft.publicDescription().contains("«VPN»"));
		assertTrue(draft.impactSummary().startsWith("Затронуто клиентов: 3"));
		assertEquals(1L, draft.serviceId());
		assertEquals(3L, draft.supportLineId());
		assertEquals(5L, draft.leaderId());
		assertEquals(5L, draft.technicalOwnerId());
		assertEquals(List.of(6L), List.copyOf(draft.participantIds()));
		assertEquals(Set.of(1L, 2L, 3L), Set.copyOf(draft.clientIds()));
		assertEquals(List.of(101L, 102L, 103L), draft.taskIds());
		assertEquals(30, draft.updateIntervalMinutes(), "интервал по серьёзности задан в правиле");
		assertTrue(stored.stream().allMatch(s -> Long.valueOf(77L).equals(s.getMatchedIncidentId())));

		ArgumentCaptor<UserNotification> notifications = ArgumentCaptor.forClass(UserNotification.class);
		verify(userNotificationService, times(2)).send(notifications.capture());
		assertEquals(List.of(5L, 6L), notifications.getAllValues().stream().map(UserNotification::getUserId).toList());
		assertTrue(notifications.getValue().getMessage().startsWith("Создан черновик инцидента INC-000077."),
				notifications.getValue().getMessage());
	}

	@Test
	void createDraftKeepsP1WithManyClients() throws Exception {
		params = new Params(15, 3, 0.7, 60, true, 3, 0.6);
		outage(0.9, vpn, null);
		verdict(0.95, IncidentSeverity.P1);
		handle(1L, 10L);
		handle(2L, 11L);
		handle(3L, 12L);
		ArgumentCaptor<IncidentService.SystemIncidentDraft> captor = ArgumentCaptor.forClass(IncidentService.SystemIncidentDraft.class);
		verify(incidentService).createDraftForSystem(captor.capture());
		assertEquals(IncidentSeverity.P1, captor.getValue().severity());
		assertEquals(15, captor.getValue().updateIntervalMinutes());
	}

	@Test
	void nextSimilarMessageIsLinkedToCreatedDraft() throws Exception {
		params = new Params(15, 3, 0.7, 60, true, 10, 0.6);
		outage(0.9, vpn, null);
		handle(1L, 10L);
		handle(2L, 11L);
		handle(3L, 12L);

		// Jev не сопоставил сообщение с инцидентом, но сервис и симптом те же — привязка к созданному черновику
		assertEquals(IncidentSignalDecision.MATCHED_INCIDENT, handle(4L, 13L));
		verify(incidentService).linkTasksForSystem(77L, List.of(104L));
		verify(incidentService).addSystemNote(eq(77L), contains("заявка #104"));
		verify(incidentService, times(1)).createDraftForSystem(any());
	}

	@Test
	void jevMatchedIncidentLinksTaskInDraftMode() throws Exception {
		params = new Params(15, 3, 0.7, 60, true, 10, 0.6);
		SupportIncident incident = SupportIncident.builder().id(7L).incidentNumber("INC-000007").title("VPN недоступен")
				.status(IncidentStatus.DIAGNOSIS).build();
		incidents.put(7L, incident);
		outage(0.9, vpn, incident);
		assertEquals(IncidentSignalDecision.MATCHED_INCIDENT, handle(1L, 10L));
		verify(incidentService).linkTasksForSystem(7L, List.of(101L));
	}

	@Test
	void notifyOnlyTriggerDoesNotBlockDraftAfterSchemeSwitch() throws Exception {
		outage(0.9, vpn, null);
		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 12L)); // схема «только уведомлять»
		verifyNoInteractions(incidentService);

		params = new Params(15, 3, 0.7, 60, true, 10, 0.6);
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(4L, 13L));
		verify(incidentService).createDraftForSystem(any());
	}

	@Test
	void numbersComeFromTheRuleNotFromCode() throws Exception {
		// «Хватит двух клиентов» — просто другое число в правиле
		params = new Params(15, 2, 0.7, 60, false, 10, 0.6);
		outage(0.9, vpn, null);
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 10L));
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(2L, 11L));
	}

	@Test
	void windowFromTheRuleIsUsedForCounting() throws Exception {
		params = new Params(45, 3, 0.7, 60, false, 10, 0.6);
		outage(0.9, vpn, null);
		handle(1L, 10L);
		ArgumentCaptor<ZonedDateTime> since = ArgumentCaptor.forClass(ZonedDateTime.class);
		verify(signalRepository).findWindow(eq(1L), eq("недоступен"), since.capture(), any());
		IncidentSignal signal = stored.getFirst();
		assertEquals(45, java.time.Duration.between(since.getValue(), signal.getCreatedAt()).toMinutes());
	}

	@Test
	void userCanLimitCountingToOneOrganizationByEditingTheRule() throws Exception {
		organizationOfClient.put(1L, 10L);
		organizationOfClient.put(2L, 10L);
		organizationOfClient.put(3L, 20L);
		outage(0.9, vpn, null);

		// как есть: клиенты любых организаций складываются
		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 12L));

		// после правки ноды подсчёта: «считать только обращения той же организации»
		stored.clear();
		ruleEdit = rule -> rule.replace("incident.countSignals(15)", "incident.countSignals(15, 'organization')");
		handle(1L, 20L);
		handle(2L, 21L);
		assertEquals(IncidentSignalDecision.COUNTED, handle(3L, 22L), "клиент другой организации не набирает порог");
	}

	@Test
	void seededRuleIsAValidWorkflowGraph() throws Exception {
		for (boolean draft : new boolean[]{false, true}) {
			JsonNode rule = mapper.readTree(V62__jev_incident_detector_rule.workflow(new Params(15, 3, 0.7, 60, draft, 10, 0.6)));
			Set<String> ids = new HashSet<>();
			rule.path("nodes").forEach(node -> assertTrue(ids.add(node.path("id").asText()), "duplicate node id"));
			assertTrue(ids.contains(rule.path("entryNodeId").asText()));
			Map<String, Set<String>> handles = new HashMap<>();
			for (JsonNode edge : rule.path("edges")) {
				assertTrue(ids.contains(edge.path("source").asText()) && ids.contains(edge.path("target").asText()), edge.toString());
				handles.computeIfAbsent(edge.path("source").asText(), key -> new HashSet<>()).add(edge.path("sourceHandle").asText());
			}
			for (JsonNode node : rule.path("nodes")) {
				Set<String> actual = handles.getOrDefault(node.path("id").asText(), Set.of());
				switch (node.path("type").asText()) {
					case "END" -> assertTrue(actual.isEmpty(), node.path("id").asText());
					case "CONDITION" -> assertEquals(Set.of("true", "false"), actual, node.path("id").asText());
					default -> assertEquals(Set.of("next"), actual, node.path("id").asText());
				}
			}
		}
	}

	@Test
	void severityParsing() {
		assertEquals(IncidentSeverity.P2, IncidentDetectorService.severityOf(" p2 "));
		assertThrows(IllegalArgumentException.class, () -> IncidentDetectorService.severityOf("P9"));
	}

	private void verdict(double sameProblem, IncidentSeverity severity) {
		when(jevService.verifyBatch(anyString(), anyList())).thenReturn(Optional.of(
				new JevService.BatchVerdict(sameProblem, severity, 3 - severity.ordinal(), "сеть")));
	}

	private void outage(double probability, ru.ravel.ItDesk.model.Service service, SupportIncident matched) {
		when(jevService.analyzeOutage(anyString(), anyString(), any(), anyList()))
				.thenReturn(Optional.of(new JevService.OutageSignal(probability, service, "недоступен", matched, 1.0)));
	}

	/** Приходит сообщение клиента; возвращает решение по нему после прохода правила. */
	private IncidentSignalDecision handle(Long clientId, Long messageId) throws Exception {
		int before = stored.size();
		runRule(clientId, messageId);
		assertEquals(before + 1, stored.size(), "rule must create exactly one signal");
		return stored.getLast().getDecision();
	}

	private void runRule(Long clientId, Long messageId) throws Exception {
		JsonNode rule = mapper.readTree(ruleEdit.apply(V62__jev_incident_detector_rule.workflow(params)));
		Map<String, JsonNode> nodes = new HashMap<>();
		rule.path("nodes").forEach(node -> nodes.put(node.path("id").asText(), node));
		Map<String, String> edges = new HashMap<>();
		rule.path("edges").forEach(edge -> edges.put(edge.path("source").asText() + "/" + edge.path("sourceHandle").asText(),
				edge.path("target").asText()));

		ObjectNode payload = mapper.createObjectNode();
		payload.putObject("message").put("id", messageId).put("text", "VPN не работает");
		payload.putObject("client").put("id", clientId);
		Event event = Event.builder().triggerType(TriggerType.MESSAGE_INCOMING).payload(payload).build();
		AutomationExecutionContext ctx = new AutomationExecutionContext(
				AutomationTrigger.builder().id(1L).name(V62__jev_incident_detector_rule.RULE_NAME).build(), event);

		String id = rule.path("entryNodeId").asText();
		for (int step = 0; id != null && step < 200; step++) {
			JsonNode node = nodes.get(id);
			JsonNode config = node.path("config");
			switch (node.path("type").asText()) {
				case "ACTION" -> {
					runtime.executeActions(config.path("actionScript").asText(), ctx);
					id = edges.get(id + "/next");
				}
				case "CONDITION" -> id = edges.get(id + "/" + runtime.evaluateExpression(config.path("expression").asText(), payload));
				case "SET_VARIABLE" -> {
					values.setVariable(payload, config.path("name").asText(), values.resolve(payload, config.path("expression").asText()));
					id = edges.get(id + "/next");
				}
				case "END" -> id = null;
				default -> id = edges.get(id + "/next");
			}
		}
		assertNull(id, "rule did not finish");
	}

	/** Исполнитель действий с настоящими перечисленными зависимостями, остальные — заглушки. */
	private static AutomationActionExecutor executor(Map<Class<?>, Object> real) throws Exception {
		Constructor<?> constructor = AutomationActionExecutor.class.getDeclaredConstructors()[0];
		Object[] args = Arrays.stream(constructor.getParameterTypes())
				.map(type -> real.containsKey(type) ? real.get(type) : mock(type))
				.toArray();
		return (AutomationActionExecutor) constructor.newInstance(args);
	}
}

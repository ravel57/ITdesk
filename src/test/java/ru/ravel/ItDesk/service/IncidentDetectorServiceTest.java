package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.ServiceMonitoringStatus;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ravel.ItDesk.model.incident.*;
import ru.ravel.ItDesk.repository.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IncidentDetectorServiceTest {

	private JevService jevService;
	private JevSettingsService settingsService;
	private IncidentSignalRepository signalRepository;
	private IncidentRepository incidentRepository;
	private TaskRepository taskRepository;
	private IncidentService incidentService;
	private UserNotificationService userNotificationService;
	private IncidentDetectorService detector;
	private final List<IncidentSignal> stored = new ArrayList<>();
	private final Map<Long, SupportIncident> incidents = new HashMap<>();
	private final ru.ravel.ItDesk.model.Service vpn = ru.ravel.ItDesk.model.Service.builder()
			.id(1L).code("vpn").name("VPN").build();

	@BeforeEach
	void setUp() {
		jevService = mock(JevService.class);
		settingsService = mock(JevSettingsService.class);
		signalRepository = mock(IncidentSignalRepository.class);
		incidentRepository = mock(IncidentRepository.class);
		taskRepository = mock(TaskRepository.class);
		incidentService = mock(IncidentService.class);
		ClientRepository clientRepository = mock(ClientRepository.class);
		MessageRepository messageRepository = mock(MessageRepository.class);
		AppSettingsService appSettingsService = mock(AppSettingsService.class);
		userNotificationService = mock(UserNotificationService.class);
		UserRepository userRepository = mock(UserRepository.class);
		// если у сервиса и линии нет ответственных — уведомляется администратор
		when(userRepository.findAll()).thenReturn(List.of(
				User.builder().id(1L).username("admin").authorities(List.of(Role.ADMIN)).build(),
				User.builder().id(2L).username("operator").authorities(List.of(Role.OPERATOR)).build()));
		detector = new IncidentDetectorService(jevService, settingsService, signalRepository, incidentRepository,
				clientRepository, messageRepository, taskRepository, incidentService, appSettingsService,
				userNotificationService, userRepository, mock(PlatformTransactionManager.class), mock(JevDecisionRecorder.class));

		when(appSettingsService.getGeneralSettings()).thenReturn(new AppSettings());
		when(clientRepository.findById(anyLong())).thenAnswer(inv -> Optional.of(Client.builder().id(inv.getArgument(0)).build()));
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
		mode(IncidentDetectorMode.NOTIFY_ONLY, 10);
		when(settingsService.effective()).thenReturn(new JevSettingsService.JevConfig(
				true, "https://api.typesafe.ai/v1/systemone", "key", "jev-latest",
				ru.ravel.ItDesk.model.jev.JevThreshold.uniform(0.6), 0.6, 0.25, List.of(), 10000));
		when(signalRepository.save(any())).thenAnswer(inv -> {
			IncidentSignal signal = inv.getArgument(0);
			// equals у сущности сравнивает id, а в тесте id пустые — сравниваем по ссылке
			if (stored.stream().noneMatch(item -> item == signal)) stored.add(signal);
			return signal;
		});
		// окно: всё, что уже сохранено как сбой по тому же сервису и симптому
		when(signalRepository.findWindow(any(), any(), any(), any())).thenAnswer(inv -> stored.stream()
				.filter(s -> s.getServiceId() != null && s.getServiceId().equals(inv.getArgument(0)))
				.filter(s -> s.getSymptom().equals(inv.getArgument(1)))
				.filter(s -> s.getDecision() != IncidentSignalDecision.IGNORED
						&& s.getDecision() != IncidentSignalDecision.MATCHED_INCIDENT)
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
	void notAnOutageIsIgnored() {
		outage(0.3, vpn, null);
		assertEquals(IncidentSignalDecision.IGNORED, handle(1L, 10L));
	}

	@Test
	void outageWithoutServiceIsIgnored() {
		outage(0.95, null, null);
		assertEquals(IncidentSignalDecision.IGNORED, handle(1L, 10L));
	}

	@Test
	void notifyOnlyTriggersOnThirdDistinctClientThenCooldown() {
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
	void batchRejectedWhenMessagesDiffer() {
		outage(0.9, vpn, null);
		verdict(0.3, IncidentSeverity.P3);
		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.BATCH_REJECTED, handle(3L, 12L));
	}

	@Test
	void monitoringAlertLowersThreshold() {
		vpn.setMonitoringStatus(ServiceMonitoringStatus.CRITICAL);
		outage(0.9, vpn, null);
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 10L));
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(2L, 11L));
	}

	@Test
	void unavailableMonitoringDoesNotLowerThreshold() {
		// UNAVAILABLE — недоступен сам Zabbix или интеграция, а не сервис: порог прежний
		vpn.setMonitoringStatus(ServiceMonitoringStatus.UNAVAILABLE);
		outage(0.9, vpn, null);
		assertEquals(IncidentSignalDecision.COUNTED, handle(1L, 10L));
		assertEquals(IncidentSignalDecision.COUNTED, handle(2L, 11L));
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 12L));
	}

	@Test
	void notifyOnlyMatchedIncidentDoesNotLink() {
		SupportIncident incident = SupportIncident.builder().id(7L).incidentNumber("INC-000007").title("VPN недоступен").build();
		outage(0.9, vpn, incident);
		assertEquals(IncidentSignalDecision.MATCHED_INCIDENT, handle(1L, 10L));
		assertEquals(7L, stored.getFirst().getMatchedIncidentId());
		verifyNoInteractions(incidentService);
	}

	@Test
	void createDraftFillsFieldsAndLinksTasks() {
		mode(IncidentDetectorMode.CREATE_DRAFT, 10);
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
		assertEquals(30, draft.updateIntervalMinutes());
		assertTrue(stored.stream().allMatch(s -> Long.valueOf(77L).equals(s.getMatchedIncidentId())));

		ArgumentCaptor<UserNotification> notifications = ArgumentCaptor.forClass(UserNotification.class);
		verify(userNotificationService, times(2)).send(notifications.capture());
		assertEquals(List.of(5L, 6L), notifications.getAllValues().stream().map(UserNotification::getUserId).toList());
		assertTrue(notifications.getValue().getMessage().startsWith("Создан черновик инцидента INC-000077."),
				notifications.getValue().getMessage());
	}

	@Test
	void createDraftKeepsP1WithManyClients() {
		mode(IncidentDetectorMode.CREATE_DRAFT, 3);
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
	void nextSimilarMessageIsLinkedToCreatedDraft() {
		mode(IncidentDetectorMode.CREATE_DRAFT, 10);
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
	void jevMatchedIncidentLinksTaskInDraftMode() {
		mode(IncidentDetectorMode.CREATE_DRAFT, 10);
		SupportIncident incident = SupportIncident.builder().id(7L).incidentNumber("INC-000007").title("VPN недоступен")
				.status(IncidentStatus.DIAGNOSIS).build();
		outage(0.9, vpn, incident);
		assertEquals(IncidentSignalDecision.MATCHED_INCIDENT, handle(1L, 10L));
		verify(incidentService).linkTasksForSystem(7L, List.of(101L));
	}

	@Test
	void notifyOnlyTriggerDoesNotBlockDraftAfterModeSwitch() {
		outage(0.9, vpn, null);
		handle(1L, 10L);
		handle(2L, 11L);
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(3L, 12L)); // режим «только уведомлять"
		verifyNoInteractions(incidentService);

		mode(IncidentDetectorMode.CREATE_DRAFT, 10);
		assertEquals(IncidentSignalDecision.TRIGGERED, handle(4L, 13L));
		verify(incidentService).createDraftForSystem(any());
	}

	@Test
	void severityLimit() {
		assertEquals(IncidentSeverity.P2, IncidentDetectorService.limitSeverity(IncidentSeverity.P1, 9, 10));
		assertEquals(IncidentSeverity.P1, IncidentDetectorService.limitSeverity(IncidentSeverity.P1, 10, 10));
		assertEquals(IncidentSeverity.P3, IncidentDetectorService.limitSeverity(IncidentSeverity.P3, 1, 10));
	}

	private void mode(IncidentDetectorMode mode, int p1MinClients) {
		when(settingsService.incidentDetector()).thenReturn(
				new JevSettingsService.IncidentDetectorConfig(15, 3, 0.7, 60, mode, p1MinClients));
	}

	private void verdict(double sameProblem, IncidentSeverity severity) {
		when(jevService.verifyBatch(anyString(), anyList())).thenReturn(Optional.of(
				new JevService.BatchVerdict(sameProblem, severity, 3 - severity.ordinal(), "сеть")));
	}

	private void outage(double probability, ru.ravel.ItDesk.model.Service service, SupportIncident matched) {
		when(jevService.analyzeOutage(anyString(), anyString(), any(), anyList()))
				.thenReturn(Optional.of(new JevService.OutageSignal(probability, service, "недоступен", matched, 1.0)));
	}

	private IncidentSignalDecision handle(Long clientId, Long messageId) {
		return detector.handleIncomingMessage("test", clientId, messageId, "VPN не работает")
				.orElseThrow()
				.getDecision();
	}
}

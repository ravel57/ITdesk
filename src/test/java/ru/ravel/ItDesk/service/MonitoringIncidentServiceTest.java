package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.dto.MonitoringProblemEvent;
import ru.ravel.ItDesk.model.MonitoringProblem;
import ru.ravel.ItDesk.model.Organization;
import ru.ravel.ItDesk.model.OrganizationServiceAssignment;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.SupportLine;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.model.UserNotification;
import ru.ravel.ItDesk.model.UserNotificationEvent;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.model.incident.IncidentSeverity;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.IncidentRepository;
import ru.ravel.ItDesk.repository.MonitoringProblemRepository;
import ru.ravel.ItDesk.repository.OrganizationServiceAssignmentRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MonitoringIncidentServiceTest {

	private IncidentRepository incidentRepository;
	private IncidentService incidentService;
	private MonitoringProblemRepository problemRepository;
	private OrganizationServiceAssignmentRepository assignmentRepository;
	private ClientRepository clientRepository;
	private UserNotificationService userNotificationService;
	private MonitoringIncidentService service;
	private ru.ravel.ItDesk.model.Service oneC;

	@BeforeEach
	void setUp() {
		incidentRepository = mock(IncidentRepository.class);
		incidentService = mock(IncidentService.class);
		problemRepository = mock(MonitoringProblemRepository.class);
		ServiceRepository serviceRepository = mock(ServiceRepository.class);
		assignmentRepository = mock(OrganizationServiceAssignmentRepository.class);
		clientRepository = mock(ClientRepository.class);
		userNotificationService = mock(UserNotificationService.class);
		UserRepository userRepository = mock(UserRepository.class);
		service = new MonitoringIncidentService(incidentRepository, incidentService, problemRepository, serviceRepository,
				assignmentRepository, clientRepository, userNotificationService, userRepository,
				new AfterCommitExecutor(mock(PlatformTransactionManager.class)));

		User owner = User.builder().id(5L).username("owner").build();
		User executor = User.builder().id(6L).username("engineer").build();
		oneC = ru.ravel.ItDesk.model.Service.builder().id(1L).code("1C").name("1С").build();
		oneC.setOwner(owner);
		oneC.setExecutors(new LinkedHashSet<>(List.of(owner, executor)));
		oneC.setDefaultSupportLine(SupportLine.builder().id(3L).name("1С").build());
		when(serviceRepository.findById(1L)).thenReturn(Optional.of(oneC));
		when(userRepository.findAll()).thenReturn(List.of(User.builder().id(1L).authorities(List.of(Role.ADMIN)).build()));
		when(incidentRepository.findFirstByServicesIdAndStatusNotInOrderByIdDesc(anyLong(), any())).thenReturn(Optional.empty());
		when(incidentService.createDraftForSystem(any(), anyString())).thenAnswer(inv -> SupportIncident.builder()
				.id(77L).incidentNumber("INC-000077").title("x").build());
	}

	@Test
	void problemCreatesDraftWithServiceTeam() {
		MonitoringIncidentService.Result result = service.handle(TriggerType.MONITORING_PROBLEM_STARTED, event(4, "OPEN"));

		assertEquals(MonitoringIncidentService.Outcome.CREATED, result.outcome());
		ArgumentCaptor<IncidentService.SystemIncidentDraft> draft = ArgumentCaptor.forClass(IncidentService.SystemIncidentDraft.class);
		verify(incidentService).createDraftForSystem(draft.capture(), eq("мониторинг Zabbix"));
		assertEquals("Мониторинг: 1С — Сервер 1С недоступен", draft.getValue().title());
		assertEquals(IncidentSeverity.P2, draft.getValue().severity());
		assertEquals(1L, draft.getValue().serviceId());
		assertEquals(3L, draft.getValue().supportLineId());
		assertEquals(5L, draft.getValue().leaderId());
		assertEquals(List.of(6L), List.copyOf(draft.getValue().participantIds()));
		assertTrue(draft.getValue().description().contains("https://zabbix.local/tr_events.php?eventid=1"));
		// уведомление команде сервиса после коммита
		ArgumentCaptor<UserNotification> notice = ArgumentCaptor.forClass(UserNotification.class);
		verify(userNotificationService, times(2)).send(notice.capture());
		assertTrue(notice.getAllValues().stream().allMatch(item -> item.getEvent() == UserNotificationEvent.INCIDENT_DETECTED));
	}

	@Test
	void activeIncidentIsNotDuplicated() {
		when(incidentRepository.findFirstByServicesIdAndStatusNotInOrderByIdDesc(eq(1L), any())).thenReturn(Optional.of(
				SupportIncident.builder().id(9L).incidentNumber("INC-000009").status(IncidentStatus.DECLARED).build()));

		MonitoringIncidentService.Result result = service.handle(TriggerType.MONITORING_PROBLEM_STARTED, event(5, "OPEN"));

		assertEquals(MonitoringIncidentService.Outcome.LINKED, result.outcome());
		assertEquals(9L, result.incidentId());
		verify(incidentService, never()).createDraftForSystem(any(), anyString());
		verify(incidentService).addSystemNote(eq(9L), contains("новая проблема по сервису «1С»"));
	}

	@Test
	void disasterIsP1OnlyWithEnoughClients() {
		assertEquals(IncidentSeverity.P2, service.severity(oneC, 5), "без клиентов P1 не ставится");

		Organization organization = Organization.builder().id(4L).name("ООО Ромашка").build();
		when(assignmentRepository.findAllByServiceIdOrderByOrganizationNameAsc(1L)).thenReturn(List.of(
				OrganizationServiceAssignment.builder().organization(organization).active(true).build()));
		when(clientRepository.countByOrganizationIdIn(any())).thenReturn(12L);
		assertEquals(IncidentSeverity.P1, service.severity(oneC, 5));
		assertEquals(IncidentSeverity.P3, service.severity(oneC, 3));
		assertEquals(IncidentSeverity.P4, service.severity(oneC, 2));
	}

	@Test
	void resolvedMovesToRecoveryCheckOnlyWhenNoOpenProblems() {
		when(incidentRepository.findFirstByServicesIdAndStatusNotInOrderByIdDesc(eq(1L), any())).thenReturn(Optional.of(
				SupportIncident.builder().id(9L).incidentNumber("INC-000009").status(IncidentStatus.MITIGATION).build()));
		when(problemRepository.findAllByServiceIdAndStatusOrderBySeverityDescStartedAtDesc(1L, MonitoringProblem.Status.OPEN))
				.thenReturn(List.of(MonitoringProblem.builder().eventId("2").build()));

		assertEquals(MonitoringIncidentService.Outcome.NOTE,
				service.handle(TriggerType.MONITORING_PROBLEM_RESOLVED, event(4, "RESOLVED")).outcome());
		verify(incidentService, never()).moveToRecoveryCheckForSystem(anyLong(), anyString());

		when(problemRepository.findAllByServiceIdAndStatusOrderBySeverityDescStartedAtDesc(1L, MonitoringProblem.Status.OPEN))
				.thenReturn(List.of());
		when(incidentService.moveToRecoveryCheckForSystem(eq(9L), anyString())).thenReturn(IncidentService.RecoveryCheckResult.MOVED);
		MonitoringIncidentService.Result result = service.handle(TriggerType.MONITORING_PROBLEM_RESOLVED, event(4, "RESOLVED"));
		assertEquals(MonitoringIncidentService.Outcome.RECOVERY_CHECK, result.outcome());
		verify(incidentService).moveToRecoveryCheckForSystem(eq(9L), contains("все проблемы по сервису «1С» устранены"));
	}

	@Test
	void unmappedServiceDoesNothing() {
		MonitoringProblemEvent unmapped = new MonitoringProblemEvent(null, event(5, "OPEN").problem(), null);
		assertEquals(MonitoringIncidentService.Outcome.SKIPPED,
				service.handle(TriggerType.MONITORING_PROBLEM_STARTED, unmapped).outcome());
		verifyNoInteractions(incidentService);
	}

	@Test
	void updateWithoutIncidentCreatesDraftForOpenProblem() {
		assertEquals(MonitoringIncidentService.Outcome.CREATED,
				service.handle(TriggerType.MONITORING_PROBLEM_UPDATED, event(4, "OPEN")).outcome());
		assertEquals(MonitoringIncidentService.Outcome.SKIPPED,
				service.handle(TriggerType.MONITORING_PROBLEM_UPDATED, event(4, "RESOLVED")).outcome());
	}

	private MonitoringProblemEvent event(int severity, String status) {
		return new MonitoringProblemEvent(
				new MonitoringProblemEvent.ServiceRef(1L, "1C", "1С"),
				new MonitoringProblemEvent.Problem("1", status, "PROBLEM", "Сервер 1С недоступен", severity, "High",
						"srv-1c", "Сервер 1С", "10.0.0.5", false, null, "https://zabbix.local/tr_events.php?eventid=1",
						"2026-10-17T12:00:01+03:00", null, List.of(), null, null, null),
				new MonitoringProblemEvent.IntegrationRef(2L, "win11_2"));
	}
}

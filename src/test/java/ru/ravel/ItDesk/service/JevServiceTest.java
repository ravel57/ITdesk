package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.ravel.ItDesk.model.Priority;
import ru.ravel.ItDesk.model.TaskType;
import ru.ravel.ItDesk.repository.PriorityRepository;
import ru.ravel.ItDesk.repository.ServiceRepository;
import ru.ravel.ItDesk.repository.SupportLineRepository;
import ru.ravel.ItDesk.repository.TaskTypeRepository;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JevServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private JevClient jevClient;
	private TaskTypeRepository taskTypeRepository;
	private PriorityRepository priorityRepository;
	private JevSettingsService settingsService;
	private JevDecisionRecorder decisionRecorder;
	private JevService service;

	@BeforeEach
	void setUp() {
		jevClient = mock(JevClient.class);
		settingsService = mock(JevSettingsService.class);
		taskTypeRepository = mock(TaskTypeRepository.class);
		priorityRepository = mock(PriorityRepository.class);
		service = new JevService(
				jevClient,
				taskTypeRepository,
				mock(ServiceRepository.class),
				mock(SupportLineRepository.class),
				priorityRepository,
				mock(ServiceCatalogService.class),
				settingsService,
				decisionRecorder = mock(JevDecisionRecorder.class)
		);
		when(settingsService.effective()).thenReturn(new JevSettingsService.JevConfig(
				true, "https://api.typesafe.ai/v1/systemone", "key", "jev-latest",
				ru.ravel.ItDesk.model.jev.JevThreshold.uniform(0.6), 0.6, 0.25, List.of("Приостановленно"), 10000
		));
		when(jevClient.isEnabled()).thenReturn(true);
	}

	@Test
	void classifyAppliesTypeOnlyWhenConfident() throws Exception {
		TaskType incident = TaskType.builder().id(1L).type("Инцидент").orderNumber(1).build();
		TaskType request = TaskType.builder().id(2L).type("Запрос").orderNumber(2).build();
		when(taskTypeRepository.findAll()).thenReturn(List.of(incident, request));

		when(jevClient.evaluate(any(), any(), any())).thenReturn(Optional.of(mapper.readTree("""
				{"task_type": {"type": "choice", "choice": "Инцидент", "confidence": 0.9}}""")));
		assertEquals(incident, service.classify("test", "Не работает VPN", null).orElseThrow().type());

		when(jevClient.evaluate(any(), any(), any())).thenReturn(Optional.of(mapper.readTree("""
				{"task_type": {"type": "choice", "choice": "Инцидент", "confidence": 0.4}}""")));
		assertTrue(service.classify("test", "Не работает VPN", null).isEmpty());
	}

	@Test
	void priorityExcludesSuspendedAndChecksConfidence() throws Exception {
		Priority critical = Priority.builder().id(1L).name("Критичный").critical(true).orderNumber(1).build();
		Priority medium = Priority.builder().id(2L).name("Средний").critical(false).orderNumber(3).build();
		Priority suspended = Priority.builder().id(3L).name("Приостановленно").critical(false).orderNumber(5).build();
		when(priorityRepository.findAll()).thenReturn(List.of(medium, suspended, critical));

		when(jevClient.evaluate(any(), any(), any())).thenReturn(Optional.of(mapper.readTree("""
				{"priority": {"type": "choice", "choice": "Критичный", "confidence": 0.8}}""")));
		assertEquals(critical, service.suggestPriority("test", "Все сервисы лежат").orElseThrow());

		when(jevClient.evaluate(any(), any(), any())).thenReturn(Optional.of(mapper.readTree("""
				{"priority": {"type": "choice", "choice": "Приостановленно", "confidence": 0.99}}""")));
		assertTrue(service.suggestPriority("test", "Все сервисы лежат").isEmpty());
	}

	@Test
	void needsReplyUsesTwoThresholds() throws Exception {
		assertEquals(JevService.ReplyDecision.REQUIRED, replyFor(0.9));
		assertEquals(JevService.ReplyDecision.UNDECIDED, replyFor(0.4));
		assertEquals(JevService.ReplyDecision.NOT_REQUIRED, replyFor(0.05));
		// каждое решение попадает в журнал с уверенностью и порогом
		verify(decisionRecorder).record(anyString(), eq("needs_reply"), eq("нужен ответ"), eq(0.9), eq(0.6),
				eq(JevDecisionRecorder.Outcome.ACCEPTED));
		verify(decisionRecorder).record(anyString(), eq("needs_reply"), eq("не ясно"), eq(0.4), eq(0.6),
				eq(JevDecisionRecorder.Outcome.UNDECIDED));
	}

	@Test
	void disabledJevDoesNothing() {
		when(jevClient.isEnabled()).thenReturn(false);
		assertEquals(JevService.ReplyDecision.UNDECIDED, service.needsReply("test", "Спасибо!"));
		assertTrue(service.suggestPriority("test", "текст").isEmpty());
		assertTrue(service.classify("test", "текст", null).isEmpty());
	}

	private JevService.ReplyDecision replyFor(double probability) throws Exception {
		when(jevClient.evaluate(any(), eq("Спасибо!"), any())).thenReturn(Optional.of(mapper.readTree(
				"{\"needs_reply\": {\"type\": \"noul\", \"noul\": " + probability + "}}")));
		return service.needsReply("test", "Спасибо!");
	}
}

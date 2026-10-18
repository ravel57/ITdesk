package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.incident.IncidentStatus;
import ru.ravel.ItDesk.model.incident.IncidentTaskLink;
import ru.ravel.ItDesk.model.incident.SupportIncident;
import ru.ravel.ItDesk.repository.*;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class JevAssistServiceTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private JevClient jevClient;
	private ClientRepository clientRepository;
	private TaskRepository taskRepository;
	private ServiceRepository serviceRepository;
	private ServiceCatalogService serviceCatalogService;
	private SupportLineRepository supportLineRepository;
	private IncidentRepository incidentRepository;
	private IncidentTaskLinkRepository linkRepository;
	private TaskService taskService;
	private ClientService clientService;
	private KnowledgeService knowledgeService;
	private IncidentService incidentService;
	private LlmService llmService;
	private JevAssistService service;
	private Client client;

	@BeforeEach
	void setUp() {
		jevClient = mock(JevClient.class);
		JevSettingsService settingsService = mock(JevSettingsService.class);
		clientRepository = mock(ClientRepository.class);
		taskRepository = mock(TaskRepository.class);
		serviceRepository = mock(ServiceRepository.class);
		serviceCatalogService = mock(ServiceCatalogService.class);
		PriorityRepository priorityRepository = mock(PriorityRepository.class);
		StatusRepository statusRepository = mock(StatusRepository.class);
		supportLineRepository = mock(SupportLineRepository.class);
		incidentRepository = mock(IncidentRepository.class);
		linkRepository = mock(IncidentTaskLinkRepository.class);
		taskService = mock(TaskService.class);
		clientService = mock(ClientService.class);
		knowledgeService = mock(KnowledgeService.class);
		incidentService = mock(IncidentService.class);
		service = new JevAssistService(jevClient, settingsService, clientRepository, taskRepository, serviceRepository,
				serviceCatalogService, priorityRepository,
				statusRepository, supportLineRepository, incidentRepository, linkRepository, taskService, clientService,
				knowledgeService, incidentService, mock(TaskRoutingService.class), llmService = mock(LlmService.class),
				mock(JevDecisionRecorder.class),
				new ru.ravel.ItDesk.component.AfterCommitExecutor(mock(org.springframework.transaction.PlatformTransactionManager.class)));

		when(settingsService.effective()).thenReturn(new JevSettingsService.JevConfig(
				true, "https://api.typesafe.ai/v1/systemone", "key", "jev-latest", ru.ravel.ItDesk.model.jev.JevThreshold.uniform(0.6), 0.6, 0.25, List.of(), 10000));
		when(jevClient.isEnabled()).thenReturn(true);
		when(priorityRepository.findByDefaultSelectionTrue()).thenReturn(Optional.of(Priority.builder().id(1L).name("Средний").build()));
		when(statusRepository.findByDefaultSelectionTrue()).thenReturn(Optional.of(Status.builder().id(1L).name("Новая").build()));
		when(taskService.newTaskForSystem(anyLong(), any())).thenAnswer(inv -> {
			Task task = inv.getArgument(1);
			task.setId(100L);
			return task;
		});
		client = Client.builder().id(1L).tasks(new ArrayList<>()).messages(new ArrayList<>()).build();
		when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
		when(clientRepository.findByTaskId(anyLong())).thenReturn(Optional.of(client));
	}

	// ---------- 1. новая заявка

	@Test
	void newRequestCreatesTaskLinkedToMessage() throws Exception {
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.9}}");
		assertEquals(List.of(100L), service.createTaskIfNeeded("t", 1L, 50L, "Не печатает принтер\nв бухгалтерии"));
		ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
		verify(taskService).newTaskForSystem(eq(1L), task.capture());
		assertEquals("Не печатает принтер", task.getValue().getName());
		assertEquals(50L, task.getValue().getLinkedMessageId());
	}

	@Test
	void continuationOfOpenTaskDoesNotCreateTask() throws Exception {
		client.getTasks().add(task(7L, "VPN не работает", false));
		answers("{\"kind\": {\"choice\": \"existing_task\", \"confidence\": 0.8},"
				+ " \"open_7\": {\"noul\": 0.8}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 50L, "Вот скриншот ошибки").isEmpty());
		verify(taskService, never()).newTaskForSystem(anyLong(), any());
	}

	@Test
	void reopenOfStillOpenTaskDoesNotCreateTask() throws Exception {
		// «Всё ещё не работает» по открытой заявке: Jev выбрал «reopen», но связанная заявка открыта
		client.getTasks().add(task(33L, "Не работает интернет", false));
		Task closed = task(30L, "Старая заявка", true);
		closed.setClosedAt(ZonedDateTime.now().minusDays(1));
		client.getTasks().add(closed);
		answers("{\"kind\": {\"choice\": \"reopen\", \"confidence\": 0.61},"
				+ " \"open_33\": {\"noul\": 0.92}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 80L, "Все еще не работает сеть на компьютере").isEmpty());
		verify(taskService, never()).newTaskForSystem(anyLong(), any());
	}

	@Test
	void newQuestionOnSimilarTopicCreatesTask() throws Exception {
		// «Как подключиться к ВПН?» при открытой «Не работает интернет»: новый вопрос, с открытой не совпадает
		client.getTasks().add(task(33L, "Не работает интернет", false));
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.71},"
				+ " \"open_33\": {\"noul\": 0.34}}");
		assertEquals(1, service.createTaskIfNeeded("t", 1L, 93L, "Как подключиться к рабочему ВПН?").size());
	}

	@Test
	void messageWithTwoServiceProblemsCreatesTwoTasks() throws Exception {
		services(internet(), oneC());
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.9},"
				+ " \"svc_3\": {\"noul\": 0.95}, \"svc_4\": {\"noul\": 0.9}, \"svc_other\": {\"noul\": 0.05}}");
		assertEquals(2, service.createTaskIfNeeded("t", 1L, 107L, "Не работает интернет и 1С").size());
		ArgumentCaptor<Task> tasks = ArgumentCaptor.forClass(Task.class);
		verify(taskService, times(2)).newTaskForSystem(eq(1L), tasks.capture());
		assertEquals("Интернет: Не работает интернет и 1С", tasks.getAllValues().get(0).getName());
		assertEquals(3L, tasks.getAllValues().get(0).getService().getId());
		assertEquals(107L, tasks.getAllValues().get(0).getLinkedMessageId(), "message linked to the first task");
		assertEquals("1C: Не работает интернет и 1С", tasks.getAllValues().get(1).getName());
		assertNull(tasks.getAllValues().get(1).getLinkedMessageId());
	}

	@Test
	void uncertainKindStillSplitsByConfidentServices() throws Exception {
		// «1с не подключается и база данных сломалась»: тип сообщения 0.49 (ниже порога), сервисы уверенно
		ru.ravel.ItDesk.model.Service database = ru.ravel.ItDesk.model.Service.builder().id(1L).code("DB_01").name("База данных").build();
		services(oneC(), database);
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.49},"
				+ " \"svc_4\": {\"noul\": 0.93}, \"svc_1\": {\"noul\": 0.9}, \"svc_other\": {\"noul\": 0.1}}");
		assertEquals(2, service.createTaskIfNeeded("t", 1L, 109L, "1с не подключается и база данных сломалась").size());
	}

	@Test
	void uncertainKindWithoutServicesFallsBack() throws Exception {
		services(oneC());
		client.getTasks().add(task(7L, "Открытая", false));
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.3}, \"svc_4\": {\"noul\": 0.2}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 110L, "Хм").isEmpty(), "open task exists -> fallback skip");
	}

	@Test
	void problemCoveredByOpenTaskIsSkipped() throws Exception {
		services(internet(), oneC());
		Task openOneC = task(43L, "1С не запускается", false);
		openOneC.setService(oneC());
		client.getTasks().add(openOneC);
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.9},"
				+ " \"svc_3\": {\"noul\": 0.95}, \"svc_4\": {\"noul\": 0.9}, \"open_43\": {\"noul\": 0.85}}");
		assertEquals(1, service.createTaskIfNeeded("t", 1L, 107L, "Не работает интернет и 1С").size());
		ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
		verify(taskService).newTaskForSystem(eq(1L), task.capture());
		assertEquals(3L, task.getValue().getService().getId(), "only internet is new");
	}

	@Test
	void repeatOnlyForServiceOfClosedTask() throws Exception {
		services(internet(), oneC());
		Task closed = task(33L, "Не работает интернет", true);
		closed.setService(internet());
		closed.setClosedAt(ZonedDateTime.now().minusDays(1));
		client.getTasks().add(closed);
		answers("{\"kind\": {\"choice\": \"reopen\", \"confidence\": 0.8},"
				+ " \"related_task\": {\"choice\": \"#33\", \"confidence\": 0.9},"
				+ " \"svc_3\": {\"noul\": 0.9}, \"svc_4\": {\"noul\": 0.9}}");
		service.createTaskIfNeeded("t", 1L, 107L, "Опять нет интернета и 1С");
		ArgumentCaptor<Task> tasks = ArgumentCaptor.forClass(Task.class);
		verify(taskService, times(2)).newTaskForSystem(eq(1L), tasks.capture());
		assertEquals("Повторно: Не работает интернет", tasks.getAllValues().get(0).getName());
		assertEquals("1C: Опять нет интернета и 1С", tasks.getAllValues().get(1).getName());
	}

	@Test
	void thanksDoNotCreateTaskEvenWithoutOpenTasks() throws Exception {
		answers("{\"kind\": {\"choice\": \"no_action\", \"confidence\": 0.95}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 50L, "Спасибо большое!").isEmpty());
		verify(taskService, never()).newTaskForSystem(anyLong(), any());
	}

	@Test
	void reopenCreatesRepeatTask() throws Exception {
		Task closed = task(8L, "Почта не отправляет письма", true);
		closed.setClosedAt(ZonedDateTime.now().minusDays(1));
		client.getTasks().add(closed);
		answers("{\"kind\": {\"choice\": \"reopen\", \"confidence\": 0.85},"
				+ " \"related_task\": {\"choice\": \"#8\", \"confidence\": 0.85}}");
		service.createTaskIfNeeded("t", 1L, 50L, "Опять письма не уходят");
		ArgumentCaptor<Task> task = ArgumentCaptor.forClass(Task.class);
		verify(taskService).newTaskForSystem(eq(1L), task.capture());
		assertEquals("Повторно: Почта не отправляет письма", task.getValue().getName());
		assertTrue(task.getValue().getDescription().contains("#8"));
	}

	@Test
	void fallbackWithoutJevKeepsOldRule() {
		when(jevClient.isEnabled()).thenReturn(false);
		assertFalse(service.createTaskIfNeeded("t", 1L, 50L, "Помогите").isEmpty(), "no open tasks -> create");
		client.getTasks().add(task(7L, "Открытая", false));
		assertTrue(service.createTaskIfNeeded("t", 1L, 51L, "Ещё вопрос").isEmpty(), "open task exists -> skip");
	}

	// ---------- 1a. продолжение разговора не создаёт заявок

	@Test
	void onSiteReplyDuringRemoteSessionDoesNotCreateTasks() throws Exception {
		// Реальный случай: «На месте готов» после «Примите подключение, починим все и сразу» при трёх открытых заявках
		ru.ravel.ItDesk.model.Service remote = ru.ravel.ItDesk.model.Service.builder().id(2L).code("RA").name("Удаленка").build();
		services(internet(), remote);
		Task network = task(53L, "что делать если пропала сеть на рабочем компе?", false);
		network.setService(internet());
		Task vpn = task(54L, "Как получить доступ к удаленке для работы?", false);
		vpn.setService(remote);
		client.getTasks().addAll(List.of(network, vpn, task(55L, "У меня место на диске Ц закончилось", false)));
		operatorMessage(141L, "Примите подключение, починим все и сразу", 10);
		answers("{\"kind\": {\"choice\": \"no_action\", \"confidence\": 0.55},"
				+ " \"svc_3\": {\"noul\": 0.73}, \"svc_2\": {\"noul\": 0.82}, \"svc_other\": {\"noul\": 0.8},"
				+ " \"open_53\": {\"noul\": 0.11}, \"open_54\": {\"noul\": 0.12}, \"open_55\": {\"noul\": 0.12}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 144L, "На месте готов").isEmpty());
		verify(taskService, never()).newTaskForSystem(anyLong(), any());
	}

	@Test
	void uncertainKindLeaningToExistingTaskDoesNotCreate() throws Exception {
		services(internet());
		client.getTasks().add(task(7L, "Открытая", false));
		answers("{\"kind\": {\"choice\": \"existing_task\", \"confidence\": 0.5}, \"svc_3\": {\"noul\": 0.9}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 50L, "Сеть опять отваливается, я писал выше про это").isEmpty());
	}

	@Test
	void shortReplyInActiveDialogIsContinuationUnlessConfidentlyNew() throws Exception {
		services(internet());
		operatorMessage(40L, "Перезагрузите роутер, пожалуйста", 5);
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.5}, \"svc_3\": {\"noul\": 0.9}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 50L, "Ок, сделал").isEmpty(), "uncertain kind -> continuation");

		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.95}, \"svc_3\": {\"noul\": 0.9}}");
		assertEquals(1, service.createTaskIfNeeded("t", 1L, 51L, "А интернета нет").size(), "confidently new -> task");
	}

	@Test
	void autoreplyDoesNotStartDialog() throws Exception {
		services(internet());
		Message autoreply = Message.builder().id(39L).text("Здравствуйте! Это автоответ").isSent(true).isComment(false)
				.user(mock(SystemUser.class)).date(ZonedDateTime.now().minusMinutes(1)).build();
		client.getMessages().add(autoreply);
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.5}, \"svc_3\": {\"noul\": 0.9}}");
		assertEquals(1, service.createTaskIfNeeded("t", 1L, 50L, "Нет интернета").size());
	}

	@Test
	void openTaskOnSameServiceBlocksNewTaskUntilProvenDifferent() throws Exception {
		services(internet());
		Task open = task(53L, "Пропала сеть", false);
		open.setService(internet());
		client.getTasks().add(open);
		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.9},"
				+ " \"svc_3\": {\"noul\": 0.9}, \"open_53\": {\"noul\": 0.5}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 50L, "Интернет медленный").isEmpty(), "not proven different");

		answers("{\"kind\": {\"choice\": \"new_request\", \"confidence\": 0.9},"
				+ " \"svc_3\": {\"noul\": 0.9}, \"open_53\": {\"noul\": 0.1}}");
		assertEquals(1, service.createTaskIfNeeded("t", 1L, 51L, "Не работает Wi-Fi в переговорной").size(), "proven different");
	}

	@Test
	@SuppressWarnings("unchecked")
	void jevSeesDialogWithoutCurrentMessageAndComments() throws Exception {
		operatorMessage(141L, "Примите подключение", 10);
		client.getMessages().add(Message.builder().id(142L).text("Jev: статья").isSent(true).isComment(true)
				.date(ZonedDateTime.now().minusMinutes(9)).build());
		client.getMessages().add(Message.builder().id(144L).text("На месте готов").isSent(false).isComment(false)
				.date(ZonedDateTime.now()).build());
		answers("{\"kind\": {\"choice\": \"no_action\", \"confidence\": 0.9}}");
		service.createTaskIfNeeded("t", 1L, 144L, "На месте готов");
		ArgumentCaptor<Object> state = ArgumentCaptor.forClass(Object.class);
		verify(jevClient).evaluate(anyString(), state.capture(), anyMap());
		assertEquals(List.of("оператор: Примите подключение"), ((java.util.Map<String, Object>) state.getValue()).get("dialog"));
	}

	private void operatorMessage(Long id, String text, int minutesAgo) {
		client.getMessages().add(Message.builder().id(id).text(text).isSent(true).isComment(false)
				.user(User.builder().id(1L).username("operator").build())
				.date(ZonedDateTime.now().minusMinutes(minutesAgo)).build());
	}

	// ---------- 2. база знаний

	@Test
	void suggestsArticleOnceAsInternalComment() throws Exception {
		when(knowledgeService.getKnowledgeBase()).thenReturn(List.of(
				Knowledge.builder().id(3L).title("Настройка VPN").texts(List.of("Установите клиент...")).orderNumber(1).build()));
		answers("{\"article\": {\"choice\": \"#3\", \"confidence\": 0.9}}");
		assertTrue(service.suggestKnowledgeArticle("t", 1L, "Как подключить VPN?").isPresent());
		ArgumentCaptor<Message> comment = ArgumentCaptor.forClass(Message.class);
		verify(clientService).sendMessageForSystem(eq(1L), comment.capture());
		assertTrue(comment.getValue().getIsComment());
		assertTrue(comment.getValue().getText().contains("«Настройка VPN»"));
		assertTrue(comment.getValue().getText().startsWith("Jev: может помочь статья базы знаний"), "LLM unavailable -> title only");
		assertFalse(comment.getValue().getText().contains("knowledgeId"));

		client.getMessages().add(comment.getValue());
		service.suggestKnowledgeArticle("t", 1L, "Как подключить VPN?");
		verify(clientService, times(1)).sendMessageForSystem(anyLong(), any());
	}

	@Test
	void articleCommentContainsAiSummary() throws Exception {
		when(knowledgeService.getKnowledgeBase()).thenReturn(List.of(
				Knowledge.builder().id(2L).title("Подключение к Wi-Fi").texts(List.of("Сеть Corp, логин доменный")).orderNumber(1).build()));
		when(llmService.askLlm(anyString())).thenReturn("Подключитесь к сети Corp с доменным логином.");
		answers("{\"article\": {\"choice\": \"#2\", \"confidence\": 0.9}}");
		service.suggestKnowledgeArticle("t", 1L, "Как подключиться к wifi?");
		verify(clientService).sendMessageForSystem(eq(1L), argThat(message ->
				message.getText().contains("Подключитесь к сети Corp") && !message.getText().contains("knowledgeId")));
	}

	@Test
	void sameProblemAsOpenTaskEvenIfClosedLookalikeExists() throws Exception {
		// #34 закрыта как дубль открытой #33; Jev отнёс сообщение к «повтору» #34, но оно совпадает с открытой #33
		client.getTasks().add(task(33L, "Не работает интернет", false));
		Task closed = task(34L, "Все еще не работает сеть", true);
		closed.setClosedAt(ZonedDateTime.now().minusMinutes(5));
		client.getTasks().add(closed);
		answers("{\"kind\": {\"choice\": \"reopen\", \"confidence\": 0.89},"
				+ " \"related_task\": {\"choice\": \"#34\", \"confidence\": 0.51},"
				+ " \"open_33\": {\"noul\": 0.8}}");
		assertTrue(service.createTaskIfNeeded("t", 1L, 85L, "Все еще не работает сеть на компьютере").isEmpty());
		verify(taskService, never()).newTaskForSystem(anyLong(), any());
	}

	// ---------- 3. восстановление после инцидента

	@Test
	void stillBrokenAfterRecoveryNotifiesIncident() throws Exception {
		IncidentTaskLink link = incidentLink(IncidentStatus.RESTORED);
		answers("{\"recovery\": {\"choice\": \"still_broken\", \"confidence\": 0.9}}");
		assertEquals(Optional.of(JevAssistService.RecoveryReport.STILL_BROKEN),
				service.checkIncidentRecovery("t", 1L, 50L, "Опять не работает"));
		verify(incidentService).setRecoveryConfirmationForSystem(eq(5L), eq(7L), eq(false), anyString());
		verify(incidentService).reportRecoveryProblemForSystem(eq(5L), eq(7L), contains("сообщение #50"));
		assertNotNull(link);
	}

	@Test
	void confirmedRecoveryMarksTask() throws Exception {
		incidentLink(IncidentStatus.MONITORING);
		answers("{\"recovery\": {\"choice\": \"confirmed\", \"confidence\": 0.9}}");
		service.checkIncidentRecovery("t", 1L, 50L, "Всё заработало");
		verify(incidentService).setRecoveryConfirmationForSystem(eq(5L), eq(7L), eq(true), anyString());
		verify(incidentService, never()).reportRecoveryProblemForSystem(anyLong(), anyLong(), anyString());
	}

	@Test
	void incidentNotInRecoveryIsIgnored() {
		incidentLink(IncidentStatus.DIAGNOSIS);
		assertTrue(service.checkIncidentRecovery("t", 1L, 50L, "Опять не работает").isEmpty());
		verifyNoInteractions(incidentService);
	}

	// ---------- 4. эскалация

	@Test
	void escalatesToNextLevelWhenConfident() throws Exception {
		SupportLine first = SupportLine.builder().id(1L).name("Первая линия").level(1).orderNumber(0).build();
		SupportLine second = SupportLine.builder().id(2L).name("Вторая линия").level(2).orderNumber(1).build();
		SupportLine third = SupportLine.builder().id(3L).name("Третья линия").level(3).orderNumber(2).build();
		when(supportLineRepository.findAll()).thenReturn(List.of(first, second, third));
		Task task = task(7L, "Не работает 1С", false);
		task.setSupportLine(first);
		task.setEnteredCurrentLineAt(ZonedDateTime.now().minusHours(1));

		answers("{\"needs_escalation\": {\"noul\": 0.4}}");
		assertTrue(service.escalationTarget("t", task, client).isEmpty());
		answers("{\"needs_escalation\": {\"noul\": 0.85}}");
		assertEquals(Optional.of(second), service.escalationTarget("t", task, client), "next level only");

		task.setEnteredCurrentLineAt(ZonedDateTime.now().minusMinutes(2));
		assertTrue(service.escalationTarget("t", task, client).isEmpty(), "just moved to this line");
	}

	// ---------- 5. дубли

	@Test
	void duplicateOfIncidentTaskIsLinkedToIncident() throws Exception {
		SupportIncident incident = SupportIncident.builder().id(5L).incidentNumber("INC-000005").title("VPN недоступен")
				.status(IncidentStatus.DIAGNOSIS).build();
		Task inIncident = task(7L, "VPN не подключается", false);
		Task fresh = task(9L, "Не могу зайти в VPN", false);
		when(taskRepository.findById(9L)).thenReturn(Optional.of(fresh));
		when(taskRepository.findById(7L)).thenReturn(Optional.of(inIncident));
		when(linkRepository.findFirstByTaskIdAndActiveTrue(9L)).thenReturn(Optional.empty());
		when(incidentRepository.findAllByStatusNotIn(any())).thenReturn(List.of(incident));
		when(linkRepository.findAllByIncidentIdAndActiveTrueOrderByLinkedAtAsc(5L))
				.thenReturn(List.of(IncidentTaskLink.builder().incident(incident).task(inIncident).build()));
		when(taskRepository.findOpenCreatedSince(any(), any())).thenReturn(List.of(fresh));
		when(incidentService.linkTasksForSystem(eq(5L), any())).thenReturn(List.of(9L));
		answers("{\"duplicate_of\": {\"choice\": \"#7\", \"confidence\": 0.9}}");

		var match = service.findDuplicate("t", 9L).orElseThrow();
		assertEquals(7L, match.duplicateOfTaskId());
		verify(incidentService).linkTasksForSystem(5L, List.of(9L));
		verify(incidentService).addSystemNote(eq(5L), contains("#9 — дубль заявки #7"));
	}

	@Test
	void duplicateWithoutIncidentOnlyComments() throws Exception {
		Task other = task(7L, "Принтер не печатает", false);
		Task fresh = task(9L, "Принтер выдаёт ошибку", false);
		when(taskRepository.findById(9L)).thenReturn(Optional.of(fresh));
		when(linkRepository.findFirstByTaskIdAndActiveTrue(9L)).thenReturn(Optional.empty());
		when(incidentRepository.findAllByStatusNotIn(any())).thenReturn(List.of());
		when(taskRepository.findOpenCreatedSince(any(), any())).thenReturn(List.of(fresh, other));
		answers("{\"duplicate_of\": {\"choice\": \"#7\", \"confidence\": 0.9}}");

		assertNull(service.findDuplicate("t", 9L).orElseThrow().incident());
		verify(incidentService, never()).linkTasksForSystem(anyLong(), any());
		// обе заявки этого же клиента (clientRepository.findByTaskId -> client 1)
		verify(clientService).sendMessageForSystem(eq(1L), argThat(message ->
				message.getText().contains("#9 похожа на открытую заявку #7 этого же клиента")));
	}

	@Test
	void duplicateOfAnotherClientMentionsIncident() throws Exception {
		Task other = task(7L, "Принтер не печатает", false);
		Task fresh = task(9L, "Принтер выдаёт ошибку", false);
		when(taskRepository.findById(9L)).thenReturn(Optional.of(fresh));
		when(linkRepository.findFirstByTaskIdAndActiveTrue(9L)).thenReturn(Optional.empty());
		when(incidentRepository.findAllByStatusNotIn(any())).thenReturn(List.of());
		when(taskRepository.findOpenCreatedSince(any(), any())).thenReturn(List.of(fresh, other));
		when(clientRepository.findByTaskId(7L)).thenReturn(Optional.of(Client.builder().id(2L).build()));
		answers("{\"duplicate_of\": {\"choice\": \"#7\", \"confidence\": 0.9}}");

		service.findDuplicate("t", 9L);
		verify(clientService).sendMessageForSystem(eq(1L), argThat(message ->
				message.getText().contains("#7 другого клиента") && message.getText().contains("инцидент")));
	}

	// ---------- helpers

	private ru.ravel.ItDesk.model.Service internet() {
		return ru.ravel.ItDesk.model.Service.builder().id(3L).code("INT").name("Интернет").build();
	}

	private ru.ravel.ItDesk.model.Service oneC() {
		return ru.ravel.ItDesk.model.Service.builder().id(4L).code("1C").name("1C").build();
	}

	private void services(ru.ravel.ItDesk.model.Service... services) {
		when(serviceCatalogService.getAvailableServices(any())).thenReturn(java.util.Arrays.stream(services)
				.map(service -> java.util.Map.<String, Object>of("id", service.getId())).toList());
		when(serviceRepository.findAllByActiveTrueOrderByOrderNumberAscNameAsc()).thenReturn(List.of(services));
	}

	private void answers(String json) throws Exception {
		when(jevClient.evaluate(anyString(), any(), anyMap())).thenReturn(Optional.of(mapper.readTree(json)));
	}

	private Task task(Long id, String name, boolean completed) {
		return Task.builder().id(id).name(name).completed(completed).createdAt(ZonedDateTime.now().minusHours(id))
				.lastActivity(ZonedDateTime.now().minusHours(1)).build();
	}

	private IncidentTaskLink incidentLink(IncidentStatus status) {
		Task task = task(7L, "VPN не работает", false);
		client.getTasks().add(task);
		SupportIncident incident = SupportIncident.builder().id(5L).incidentNumber("INC-000005").title("VPN недоступен")
				.status(status).build();
		IncidentTaskLink link = IncidentTaskLink.builder().incident(incident).task(task).build();
		when(linkRepository.findFirstByTaskIdAndActiveTrue(7L)).thenReturn(Optional.of(link));
		return link;
	}
}

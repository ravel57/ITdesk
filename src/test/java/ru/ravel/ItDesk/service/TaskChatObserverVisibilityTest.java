package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import ru.ravel.ItDesk.component.AfterCommitExecutor;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.config.LegacyJacksonConfig;
import ru.ravel.ItDesk.dto.FileDto;
import ru.ravel.ItDesk.dto.TaskMessageDto;
import ru.ravel.ItDesk.dto.TaskUpdatedDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Менеджер организации (OBSERVER) не получает внутренний чат заявки (Task.messages)
 * ни через HTTP, ни через WebSocket; поддержка получает его как раньше.
 */
class TaskChatObserverVisibilityTest {

	private final Organization organization = Organization.builder().id(5L).name("Org").build();
	private final User observer = user(1L, "observer", Role.OBSERVER);
	private final User operator = user(2L, "operator", Role.OPERATOR);

	private UserService userService;
	private ClientRepository clientRepository;
	private TaskRepository taskRepository;
	private Message chatMessage;
	private Task task;
	private Client client;

	@BeforeEach
	void setUp() {
		chatMessage = Message.builder().id(100L).text("внутреннее обсуждение").isComment(false)
				.fileUuid("file-uuid").fileName("log.txt").fileType("text/plain").build();
		task = Task.builder().id(10L).name("Заявка").messages(new ArrayList<>(List.of(chatMessage))).build();
		client = Client.builder().id(20L).organization(organization).tasks(new ArrayList<>(List.of(task))).build();

		userService = mock(UserService.class);
		when(userService.canUserSeeTaskChat(any())).thenCallRealMethod();
		when(userService.isAdmin(any())).thenCallRealMethod();
		when(userService.isManager(any())).thenCallRealMethod();
		when(userService.isOperator(any())).thenCallRealMethod();
		when(userService.isObserver(any())).thenCallRealMethod();
		when(userService.canUserAccessTask(any(), any(), any())).thenReturn(true);
		when(userService.getUsers()).thenReturn(List.of(observer, operator));

		clientRepository = mock(ClientRepository.class);
		when(clientRepository.findById(20L)).thenReturn(Optional.of(client));
		when(clientRepository.findByTaskId(10L)).thenReturn(Optional.of(client));
		taskRepository = mock(TaskRepository.class);
		when(taskRepository.findById(10L)).thenReturn(Optional.of(task));
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void taskUpdatedSocketHidesChatOnlyFromObserver() {
		SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
		Map<String, Object> taskDto = new HashMap<>(Map.of("id", 10L, "messages", List.of(Map.of("id", 100L))));

		webSocketService(messaging).taskUpdated(new TaskUpdatedDto(20L, taskDto));

		assertEquals(List.of(), sentTaskUpdated(messaging, "observer").getTask().get("messages"));
		assertEquals(List.of(Map.of("id", 100L)), sentTaskUpdated(messaging, "operator").getTask().get("messages"));
		assertEquals(List.of(Map.of("id", 100L)), taskDto.get("messages"), "исходное событие не меняется");
	}

	@Test
	void taskMessageSocketIsNotSentToObserver() {
		SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);

		webSocketService(messaging).taskMessage(20L, 10L, chatMessage);

		verify(messaging, never()).convertAndSendToUser(eq("observer"), anyString(), any());
		verify(messaging).convertAndSendToUser(eq("operator"), eq("/topic/task-messages/"), any(TaskMessageDto.class));
	}

	@Test
	void tasksPageHidesChatOnlyFromObserver() {
		TaskService taskService = taskService();

		loginAs(observer);
		assertEquals(List.of(), firstPageTask(taskService).get("messages"));

		loginAs(operator);
		List<?> operatorMessages = (List<?>) firstPageTask(taskService).get("messages");
		assertEquals(1, operatorMessages.size());
		assertEquals(100L, ((Number) ((Map<?, ?>) operatorMessages.getFirst()).get("id")).longValue());
		assertEquals(List.of(chatMessage), task.getMessages(), "сущность заявки не меняется");
	}

	@Test
	void taskFilesFromChatAreHiddenFromObserver() {
		ClientService clientService = clientService();

		loginAs(observer);
		assertEquals(List.of(), clientService.getTaskFiles(20L, 10L));

		loginAs(operator);
		List<FileDto> operatorFiles = clientService.getTaskFiles(20L, 10L);
		assertEquals(1, operatorFiles.size());
		assertEquals(List.of(chatMessage), task.getMessages(), "сущность заявки не меняется");
	}


	private WebSocketService webSocketService(SimpMessagingTemplate messaging) {
		return new WebSocketService(messaging, mock(ClientPortalSocketService.class), userService,
				clientRepository, taskRepository);
	}

	private TaskUpdatedDto sentTaskUpdated(SimpMessagingTemplate messaging, String username) {
		var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
		verify(messaging).convertAndSendToUser(eq(username), eq("/topic/task-updated/"), captor.capture());
		return (TaskUpdatedDto) captor.getValue();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> firstPageTask(TaskService taskService) {
		Map<String, Object> page = taskService.getTasksPage(Map.of());
		List<Map<String, Object>> tasks = (List<Map<String, Object>>) page.get("tasks");
		assertEquals(1, tasks.size());
		return tasks.getFirst();
	}

	private TaskService taskService() {
		Query query = mock(Query.class);
		when(query.getSingleResult()).thenReturn(1L);
		List<Object[]> rows = List.<Object[]>of(new Object[]{task, client});
		when(query.getResultList()).thenReturn(rows);
		EntityManager entityManager = mock(EntityManager.class);
		when(entityManager.createQuery(anyString())).thenReturn(query);
		AppSettingsRepository appSettingsRepository = mock(AppSettingsRepository.class);
		when(appSettingsRepository.findAll()).thenReturn(List.of());
		ObjectMapper objectMapper = new LegacyJacksonConfig().legacyObjectMapper();
		return new TaskService(taskRepository, mock(TaskTypeRepository.class), clientRepository,
				mock(MessageRepository.class), mock(OrganizationService.class), mock(SlaRepository.class),
				mock(GlobalSearchService.class), mock(EventPublisher.class), mock(SlaPauseRepository.class),
				appSettingsRepository, objectMapper, entityManager, userService,
				mock(UserNotificationService.class), mock(SupportLineRepository.class), mock(TaskRoutingService.class),
				mock(OlaService.class), mock(OlaWorkingTimeService.class), mock(ServiceCatalogService.class),
				mock(WebSocketService.class), mock(AfterCommitExecutor.class));
	}

	private ClientService clientService() {
		return new ClientService(clientRepository, taskRepository, mock(MessageRepository.class),
				mock(TelegramService.class), userService, mock(OrganizationService.class),
				mock(SlaRepository.class), mock(WebSocketService.class), mock(EventPublisher.class),
				mock(UserRepository.class), mock(GlobalSearchService.class), mock(SlaPauseRepository.class),
				mock(UserNotificationService.class), mock(PendingFileUploadRegistry.class));
	}

	private void loginAs(User user) {
		when(userService.getCurrentUser()).thenReturn(user);
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken(user.getUsername(), null, user.getAuthorities()));
	}

	private User user(Long id, String username, Role role) {
		User user = new User();
		user.setId(id);
		user.setUsername(username);
		user.setAuthorities(List.of(role));
		user.setAvailableOrganizations(List.of(organization));
		return user;
	}
}

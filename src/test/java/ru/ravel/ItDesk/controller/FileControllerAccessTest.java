package ru.ravel.ItDesk.controller;

import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.ravel.ItDesk.component.PendingFileUploadRegistry;
import ru.ravel.ItDesk.component.SlidingFileCache;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.service.UserService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Выдача файлов: доступ к клиенту недостаточен — учитываются комментарии, внутренний чат заявки
 * и видимость линии поддержки.
 */
class FileControllerAccessTest {

	private static final String UUID = "file-uuid";

	private final Organization organization = Organization.builder().id(5L).name("Org").build();
	private final User portalClient = user(1L, Role.CLIENT);
	private final User observer = user(2L, Role.OBSERVER);
	private final User lineMember = user(3L, Role.OPERATOR);
	private final User otherOperator = user(4L, Role.OPERATOR);
	private final User manager = user(5L, Role.MANAGER);

	private Client client;
	private ClientRepository clientRepository;
	private UserService userService;
	private FileController controller;

	@BeforeEach
	void setUp() {
		client = Client.builder().id(20L).organization(organization).build();
		portalClient.setClientId(20L);

		clientRepository = mock(ClientRepository.class);
		when(clientRepository.findClientMessageFileAccessRowsByFileUuid(UUID)).thenReturn(List.of());
		when(clientRepository.findTaskMessageFileAccessRowsByFileUuid(UUID)).thenReturn(List.of());
		userService = mock(UserService.class, CALLS_REAL_METHODS);
		SlidingFileCache cache = mock(SlidingFileCache.class);
		when(cache.getOrLoad(anyString(), anyString(), any())).thenReturn(new byte[]{1});
		controller = new FileController(mock(MinioClient.class), clientRepository, userService, cache,
				mock(PendingFileUploadRegistry.class));
	}

	@Test
	void plainClientMessageFileIsAvailableToEveryoneWithClientAccess() {
		clientMessageFile(Message.builder().id(100L).fileUuid(UUID).fileName("a.txt").build());

		for (User user : List.of(portalClient, observer, lineMember, otherOperator, manager)) {
			assertTrue(canDownload(user), user.getUsername());
		}
	}

	@Test
	void commentFileIsHiddenFromPortalClientAndObserver() {
		clientMessageFile(Message.builder().id(100L).fileUuid(UUID).fileName("a.txt").isComment(true).build());

		assertForbidden(portalClient);
		assertForbidden(observer);
		assertTrue(canDownload(lineMember));
		assertTrue(canDownload(manager));
	}

	@Test
	void deletedMessageFileIsHiddenFromPortalClient() {
		clientMessageFile(Message.builder().id(100L).fileUuid(UUID).fileName("a.txt").deleted(true).build());

		assertForbidden(portalClient);
	}

	@Test
	void taskChatFileIsHiddenFromPortalClientAndObserverAndRespectsSupportLine() {
		SupportLine line = SupportLine.builder().id(7L).name("L2")
				.visibilityMode(SupportLineVisibilityMode.LINE_MEMBERS)
				.members(List.of(lineMember)).build();
		Task task = Task.builder().id(10L).supportLine(line).build();
		taskMessageFile(task, Message.builder().id(101L).fileUuid(UUID).fileName("log.txt").build());

		assertForbidden(portalClient);
		assertForbidden(observer);
		assertForbidden(otherOperator);
		assertTrue(canDownload(lineMember));
		assertTrue(canDownload(manager));
	}

	@Test
	void fileIsAvailableWhenAnyLinkedMessageIsAllowed() {
		SupportLine line = SupportLine.builder().id(7L).name("L2")
				.visibilityMode(SupportLineVisibilityMode.LINE_MEMBERS).build();
		Task task = Task.builder().id(10L).supportLine(line).build();
		clientMessageFile(Message.builder().id(100L).fileUuid(UUID).fileName("a.txt").build());
		taskMessageFile(task, Message.builder().id(101L).fileUuid(UUID).fileName("a.txt").build());

		assertTrue(canDownload(otherOperator));
	}


	private void clientMessageFile(Message message) {
		ClientRepository.FileAccessRow row = new ClientRepository.FileAccessRow() {
			@Override
			public Client getClient() {
				return client;
			}

			@Override
			public Message getMessage() {
				return message;
			}
		};
		when(clientRepository.findClientMessageFileAccessRowsByFileUuid(UUID)).thenReturn(List.of(row));
	}

	private void taskMessageFile(Task task, Message message) {
		ClientRepository.TaskFileAccessRow row = new ClientRepository.TaskFileAccessRow() {
			@Override
			public Task getTask() {
				return task;
			}

			@Override
			public Client getClient() {
				return client;
			}

			@Override
			public Message getMessage() {
				return message;
			}
		};
		when(clientRepository.findTaskMessageFileAccessRowsByFileUuid(UUID)).thenReturn(List.of(row));
	}

	private boolean canDownload(User user) {
		doReturn(user).when(userService).getCurrentUser();
		try {
			return controller.documentFile(UUID).getStatusCode().is2xxSuccessful();
		} catch (ResponseStatusException e) {
			assertEquals(HttpStatus.FORBIDDEN, e.getStatusCode());
			return false;
		}
	}

	private void assertForbidden(User user) {
		assertFalse(canDownload(user), user.getUsername());
	}

	private User user(Long id, Role role) {
		User user = new User();
		user.setId(id);
		user.setUsername(role.name().toLowerCase() + id);
		user.setAuthorities(List.of(role));
		user.setAvailableOrganizations(List.of(organization));
		return user;
	}
}

package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.ravel.ItDesk.controller.SlaController;
import ru.ravel.ItDesk.dto.SlaInfoBatchRequest;
import ru.ravel.ItDesk.dto.SlaInfoDto;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.repository.*;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Батчевый SLA: доступ к каждой заявке, лимит пачки, пустой список, неизвестные id и отсутствие N+1.
 */
class SlaInfoServiceTest {

	private final User user = User.builder().id(1L).build();
	private final Client client = Client.builder().id(10L).build();

	private SlaPauseRepository pauseRepository;
	private TaskRepository taskRepository;
	private ClientRepository clientRepository;
	private UserService userService;
	private SlaInfoService service;


	@BeforeEach
	void setUp() {
		pauseRepository = mock(SlaPauseRepository.class);
		taskRepository = mock(TaskRepository.class);
		clientRepository = mock(ClientRepository.class);
		userService = mock(UserService.class);
		AppSettingsRepository appSettingsRepository = mock(AppSettingsRepository.class);
		when(appSettingsRepository.findAll()).thenReturn(List.of());
		when(userService.getCurrentUser()).thenReturn(user);
		when(userService.canUserAccessTask(any(), any(), any())).thenReturn(true);
		service = new SlaInfoService(new SlaService(mock(SlaRepository.class), pauseRepository), pauseRepository,
				taskRepository, clientRepository, appSettingsRepository, userService);
	}


	@Test
	void emptyAndNullListsReturnEmptyMapWithoutQueries() {
		assertTrue(service.getInfoForCurrentUser(null).isEmpty());
		assertTrue(service.getInfoForCurrentUser(List.of()).isEmpty());
		assertTrue(service.getInfoForCurrentUser(Arrays.asList(null, null)).isEmpty());

		verifyNoInteractions(taskRepository, clientRepository, pauseRepository);
	}


	@Test
	void unknownIdsAreSkipped() {
		linkClient(1L);
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(List.of(task(1L, null)));

		Map<Long, SlaInfoDto> result = service.getInfoForCurrentUser(List.of(1L, 404L));

		assertEquals(Set.of(1L), result.keySet());
	}


	@Test
	void taskWithoutSlaGetsEmptyInfoLikeSingleEndpoint() {
		linkClient(1L);
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(List.of(task(1L, null)));

		SlaInfoDto info = service.getInfoForCurrentUser(List.of(1L)).get(1L);

		assertEquals(new SlaInfoDto(false, null, 0L, 0L, 0.0, 0L), info);
	}


	@Test
	void inaccessibleTasksAreNotReturned() {
		linkClient(1L, 2L);
		Task allowed = task(1L, null);
		Task denied = task(2L, null);
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(List.of(allowed, denied));
		when(userService.canUserAccessTask(user, client, denied)).thenReturn(false);

		Map<Long, SlaInfoDto> result = service.getInfoForCurrentUser(List.of(1L, 2L));

		assertEquals(Set.of(1L), result.keySet());
	}


	@Test
	void taskWithoutClientIsNotReturned() {
		when(clientRepository.findClientsByTaskIds(any())).thenReturn(List.of());
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(List.of(task(1L, null)));

		assertTrue(service.getInfoForCurrentUser(List.of(1L)).isEmpty());
	}


	@Test
	void batchOverLimitIsRejectedAndLimitItselfIsAccepted() {
		List<Long> tooMany = LongStream.rangeClosed(1, SlaInfoService.MAX_BATCH_SIZE + 1).boxed().toList();
		assertThrows(IllegalArgumentException.class, () -> service.getInfoForCurrentUser(tooMany));

		// дубликаты не считаются: уникальных ровно MAX_BATCH_SIZE
		List<Long> withDuplicates = new ArrayList<>(tooMany.subList(0, SlaInfoService.MAX_BATCH_SIZE));
		withDuplicates.add(1L);
		when(clientRepository.findClientsByTaskIds(any())).thenReturn(List.of());
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(List.of());
		assertDoesNotThrow(() -> service.getInfoForCurrentUser(withDuplicates));
	}


	@Test
	void computesSameValuesAsPausesDictate() {
		ZonedDateTime now = ZonedDateTime.now();
		Sla running = sla(100L, now.minusHours(1), Duration.ofHours(4));
		Sla paused = sla(200L, now.minusHours(1), Duration.ofHours(4));
		linkClient(1L, 2L);
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(List.of(task(1L, running), task(2L, paused)));
		SlaPause closed = SlaPause.builder().sla(running).startedAt(now.minusMinutes(50)).endedAt(now.minusMinutes(20)).build();
		SlaPause open = SlaPause.builder().sla(paused).startedAt(now.minusMinutes(10)).build();
		when(pauseRepository.findAllBySlaIdIn(any())).thenReturn(List.of(closed, open));

		Map<Long, SlaInfoDto> result = service.getInfoForCurrentUser(List.of(1L, 2L));

		SlaInfoDto runningInfo = result.get(1L);
		assertFalse(runningInfo.getPaused());
		assertEquals(Duration.ofMinutes(30).getSeconds(), runningInfo.getPausedSeconds());
		assertEquals(running.getStartDate().plusHours(4).plusMinutes(30).toInstant(), runningInfo.getDeadline().toInstant());
		assertEquals(0.17, runningInfo.getSlaWorkingDays());
		assertEquals(Duration.ofHours(24).getSeconds(), runningInfo.getWorkdaySeconds());
		assertTrue(Math.abs(runningInfo.getRemainingSeconds() - Duration.ofMinutes(3 * 60 + 30).getSeconds()) <= 5);

		SlaInfoDto pausedInfo = result.get(2L);
		assertTrue(pausedInfo.getPaused());
		assertTrue(Math.abs(pausedInfo.getPausedSeconds() - Duration.ofMinutes(10).getSeconds()) <= 5);
	}


	@Test
	void batchUsesFixedNumberOfQueriesRegardlessOfSize() {
		ZonedDateTime now = ZonedDateTime.now();
		List<Task> tasks = new ArrayList<>();
		List<Long> ids = new ArrayList<>();
		for (long id = 1; id <= 50; id++) {
			tasks.add(task(id, sla(1000 + id, now, Duration.ofHours(1))));
			ids.add(id);
		}
		linkClient(ids.toArray(Long[]::new));
		when(taskRepository.findAllByIdInWithSla(any())).thenReturn(tasks);
		when(pauseRepository.findAllBySlaIdIn(any())).thenReturn(List.of());

		assertEquals(50, service.getInfoForCurrentUser(ids).size());

		verify(clientRepository, times(1)).findClientsByTaskIds(any());
		verify(taskRepository, times(1)).findAllByIdInWithSla(any());
		verify(pauseRepository, times(1)).findAllBySlaIdIn(any());
		verify(pauseRepository, never()).findAllBySlaId(any());
	}


	@Test
	void controllerRejectsOversizedBatchWithBadRequestAndAnswersEmptyForEmptyBody() {
		SlaController controller = new SlaController(mock(OrganizationService.class), mock(SlaService.class),
				mock(TaskService.class), mock(ClientRepository.class), userService, service);

		List<Long> tooMany = LongStream.rangeClosed(1, SlaInfoService.MAX_BATCH_SIZE + 1).boxed().toList();
		ResponseEntity<Object> rejected = controller.getTasksSlaInfo(new SlaInfoBatchRequest(tooMany));
		assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());

		assertEquals(Map.of(), controller.getTasksSlaInfo(new SlaInfoBatchRequest(List.of())).getBody());
		assertEquals(Map.of(), controller.getTasksSlaInfo(null).getBody());
		assertEquals(Map.of(), controller.getTasksSlaInfo(new SlaInfoBatchRequest(null)).getBody());
	}


	private void linkClient(Long... taskIds) {
		List<Object[]> rows = new ArrayList<>();
		for (Long id : taskIds) {
			rows.add(new Object[]{id, client});
		}
		when(clientRepository.findClientsByTaskIds(any())).thenReturn(rows);
	}


	private static Task task(Long id, Sla sla) {
		Task task = new Task();
		task.setId(id);
		task.setSla(sla);
		return task;
	}


	private static Sla sla(Long id, ZonedDateTime start, Duration duration) {
		Sla sla = new Sla();
		sla.setId(id);
		sla.setStartDate(start);
		sla.setDuration(duration);
		return sla;
	}
}

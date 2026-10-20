package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.SlaInfoDto;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Sla;
import ru.ravel.ItDesk.model.SlaPause;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.AppSettingsRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.SlaPauseRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;


/**
 * Расчёт SLA-информации заявок: одиночный (pause/resume/info) и батчевый.
 * Батч загружает заявки, клиентов и паузы SLA пачкой — без N+1 запросов.
 */
@Service
@RequiredArgsConstructor
public class SlaInfoService {

	/** Максимальный размер пачки в одном батч-запросе. */
	public static final int MAX_BATCH_SIZE = 200;

	private final SlaService slaService;
	private final SlaPauseRepository slaPauseRepository;
	private final TaskRepository taskRepository;
	private final ClientRepository clientRepository;
	private final AppSettingsRepository appSettingsRepository;
	private final UserService userService;


	public SlaInfoDto emptyInfo() {
		return new SlaInfoDto(false, null, 0L, 0L, 0.0, 0L);
	}


	@Transactional(readOnly = true)
	public SlaInfoDto buildInfo(Sla sla) {
		if (sla == null) {
			return emptyInfo();
		}
		List<SlaPause> pauses = sla.getId() == null
				? List.of()
				: slaPauseRepository.findAllBySlaId(sla.getId());
		return buildInfo(sla, pauses, getWorkdaySeconds(), ZonedDateTime.now());
	}


	/**
	 * SLA пачки заявок для текущего пользователя. Неизвестные и недоступные заявки в результат не попадают
	 * (наличие чужих заявок не раскрывается). Заявка без SLA получает пустую информацию, как в одиночной ручке.
	 *
	 * @throws IllegalArgumentException если в пачке больше {@link #MAX_BATCH_SIZE} уникальных id
	 */
	@Transactional(readOnly = true)
	public Map<Long, SlaInfoDto> getInfoForCurrentUser(Collection<Long> taskIds) {
		if (taskIds == null || taskIds.isEmpty()) {
			return Map.of();
		}
		Set<Long> ids = taskIds.stream()
				.filter(Objects::nonNull)
				.collect(Collectors.toCollection(LinkedHashSet::new));
		if (ids.isEmpty()) {
			return Map.of();
		}
		if (ids.size() > MAX_BATCH_SIZE) {
			throw new IllegalArgumentException("Слишком много заявок в запросе: максимум " + MAX_BATCH_SIZE);
		}

		Map<Long, Client> clientByTaskId = new HashMap<>();
		for (Object[] row : clientRepository.findClientsByTaskIds(ids)) {
			clientByTaskId.put((Long) row[0], (Client) row[1]);
		}
		List<Task> tasks = taskRepository.findAllByIdInWithSla(ids);

		User user = userService.getCurrentUser();
		Map<Long, Task> accessible = new LinkedHashMap<>();
		for (Task task : tasks) {
			Client client = clientByTaskId.get(task.getId());
			if (client != null && userService.canUserAccessTask(user, client, task)) {
				accessible.put(task.getId(), task);
			}
		}
		if (accessible.isEmpty()) {
			return Map.of();
		}

		Set<Long> slaIds = accessible.values().stream()
				.map(Task::getSla)
				.filter(Objects::nonNull)
				.map(Sla::getId)
				.filter(Objects::nonNull)
				.collect(Collectors.toSet());
		Map<Long, List<SlaPause>> pausesBySlaId = slaIds.isEmpty()
				? Map.of()
				: slaPauseRepository.findAllBySlaIdIn(slaIds).stream()
				.collect(Collectors.groupingBy(pause -> pause.getSla().getId()));

		long workdaySeconds = getWorkdaySeconds();
		ZonedDateTime now = ZonedDateTime.now();
		Map<Long, SlaInfoDto> result = new LinkedHashMap<>();
		for (Task task : accessible.values()) {
			Sla sla = task.getSla();
			if (sla == null) {
				result.put(task.getId(), emptyInfo());
				continue;
			}
			List<SlaPause> pauses = pausesBySlaId.getOrDefault(sla.getId(), List.of());
			result.put(task.getId(), buildInfo(sla, pauses, workdaySeconds, now));
		}
		return result;
	}


	private SlaInfoDto buildInfo(Sla sla, List<SlaPause> pauses, long workdaySeconds, ZonedDateTime now) {
		Duration pausedDuration = slaService.pausedDuration(pauses, now);
		ZonedDateTime deadline = slaService.deadline(sla, pausedDuration);
		long remainingSeconds = deadline == null ? 0L : Duration.between(now, deadline).getSeconds();
		return new SlaInfoDto(
				slaService.hasOpenPause(pauses),
				deadline,
				remainingSeconds,
				pausedDuration.getSeconds(),
				getSlaWorkingDays(sla, workdaySeconds),
				workdaySeconds
		);
	}


	private long getWorkdaySeconds() {
		return appSettingsRepository.findAll().stream()
				.findFirst()
				.map(AppSettings::getWorkdayDuration)
				.map(Duration::getSeconds)
				.filter(seconds -> seconds > 0)
				.orElse(Duration.ofHours(24).getSeconds());
	}


	private double getSlaWorkingDays(Sla sla, long workdaySeconds) {
		if (sla == null || sla.getDuration() == null || workdaySeconds <= 0) {
			return 0.0;
		}
		double workingDays = (double) sla.getDuration().getSeconds() / (double) workdaySeconds;
		return Math.round(workingDays * 100.0) / 100.0;
	}
}

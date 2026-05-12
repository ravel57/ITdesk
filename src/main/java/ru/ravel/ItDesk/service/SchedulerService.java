package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.model.Support;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.SupportRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
public class SchedulerService {

	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final UserService userService;
	private final LicenseStarter licenseStarter;
	private final TaskRepository taskRepository;
	private final SupportRepository supportRepository;
	private final EventPublisher eventPublisher;
	private final AutomationOutboxRepository automationOutboxRepository;
	private final GlobalSearchService globalSearchService;
	private final TaskService taskService;
	private final UserNotificationService userNotificationService;


	@Scheduled(fixedRate = 500, timeUnit = TimeUnit.MILLISECONDS)
	void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
	void supportMessages() {
		List<Support> all = supportRepository.findAll();
		if (!all.isEmpty()) {
			webSocketService.supportMessages(all.getFirst().getMessages().stream().sorted().toList());
		}
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
	void checkLicense() {
		licenseStarter.run();
	}


	@Scheduled(fixedRate = 30, timeUnit = TimeUnit.SECONDS)
	void checkFrozenTasks() {
		taskRepository.findAll().stream()
				.filter(task -> Boolean.TRUE.equals(task.getFrozen()))
				.filter(task -> task.getFrozenUntil() != null)
				.filter(task -> ZonedDateTime.now().isAfter(task.getFrozenUntil()))
				.map(Task::getId)
				.forEach(taskService::autoUnfreezeTask);
	}


	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	void checkOverdueTasks() {
		List<Task> overdueTasks = taskRepository.findOverdueNotCompleted(ZonedDateTime.now());
		for (Task task : overdueTasks) {
			if (!automationOutboxRepository.existsByTriggerTypeAndTaskId(TriggerType.TASK_OVERDUE.name(), task.getId())) {
				eventPublisher.publish(TriggerType.TASK_OVERDUE, Map.of("task", task));
			}
		}
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
	void reindexGlobalSearch() {
		globalSearchService.reindexAll();
	}


	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	void checkSlaNotifications() {
		userNotificationService.checkSlaNotifications();
	}


	@Scheduled(fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
	void checkUnansweredChatNotifications() {
		userNotificationService.checkUnansweredChatNotifications();
	}
}
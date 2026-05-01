package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.model.Client;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Support;
import ru.ravel.ItDesk.model.Task;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AutomationOutboxRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.repository.SupportRepository;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
public class SchedulerService {

	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final UserService userService;
	private final LicenseStarter licenseStarter;
	private final TaskRepository taskRepository;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());
	private final SupportRepository supportRepository;
	private final EventPublisher eventPublisher;
	private final ClientRepository clientRepository;
	private final AutomationOutboxRepository automationOutboxRepository;


	@Scheduled(fixedRate = 500)
	private void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
	}

	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
	private void supportMessages() {
		List<Support> all = supportRepository.findAll();
		if (!all.isEmpty()) {
			webSocketService.supportMessages(all.getFirst().getMessages().stream().sorted().toList());
		}
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
	private void checkLicense() {
		licenseStarter.run();
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
	private void checkFrozenTasks() {
		taskRepository.findAll().stream()
				.filter(task -> task.getFrozen() != null && task.getFrozen())
				.filter(task -> ZonedDateTime.now().isAfter(Objects.requireNonNullElse(task.getFrozenUntil(), ZonedDateTime.now())))
				.peek(task -> task.setFrozen(false))
				.peek(task -> task.setFrozenUntil(null))
				.peek(task -> task.setStatus(task.getPreviousStatus()))
				.forEach(taskRepository::save);
	}


	@Scheduled(fixedDelay = 60000)
	public void checkOverdueTasks() {
		List<Task> overdueTasks = taskRepository.findOverdueNotCompleted(ZonedDateTime.now());

		for (Task task : overdueTasks) {
			if (!automationOutboxRepository.existsByTriggerTypeAndTaskId(TriggerType.TASK_OVERDUE.name(), task.getId())) {
					eventPublisher.publish(TriggerType.TASK_OVERDUE, Map.of(
							"task", task
				));
			}
		}
	}


	@Scheduled(fixedDelay = 60000)
	public void checkInactiveClients() {
		List<Client> clients = clientRepository.findAll();

		for (Client client : clients) {
			Message lastMessage = client.getMessages().stream()
					.max(Message::compareTo)
					.orElse(null);

			if (lastMessage == null) {
				continue;
			}

			if (!lastMessage.getIsSent() && lastMessage.getDate().plusMinutes(30).isBefore(ZonedDateTime.now())) {
				if (!automationOutboxRepository.existsByTriggerTypeAndClientId(TriggerType.INACTIVITY_TIMEOUT.name(), client.getId())) {
					eventPublisher.publish(TriggerType.INACTIVITY_TIMEOUT, Map.of(
							"client", client,
							"message", lastMessage
					));
				}
			}
		}
	}

}

package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.repository.TaskRepository;

import java.time.ZonedDateTime;
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


	@Scheduled(fixedRate = 500)
	private void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
	private void checkLicense() {
		licenseStarter.run();
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
	private void checkFrozenTasks(){
		taskRepository.findAll().stream()
				.filter(task -> task.getFrozen() != null && task.getFrozen())
				.filter(task -> ZonedDateTime.now().isAfter(task.getFrozenUntil()))
				.peek(task -> task.setFrozen(false))
				.peek(task -> task.setFrozenUntil(null))
				.forEach(taskRepository::save);
	}

}

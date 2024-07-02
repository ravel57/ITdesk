package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.component.LicenseStarter;

import java.util.concurrent.TimeUnit;


@Service
@RequiredArgsConstructor
public class SchedulerService {

	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final UserService userService;
	private final LicenseStarter licenseStarter;

	private final Logger logger = LoggerFactory.getLogger(this.getClass());


	@Scheduled(fixedRate = 500)
	private void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
		try {
			webSocketService.sendClientsForObserver(clientService.getClientsForObserver(userService.getCurrentUser()));
		} catch (NullPointerException ignored) {
		}
	}


	@Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
	private void checkLicense() {
		licenseStarter.run();
	}

}

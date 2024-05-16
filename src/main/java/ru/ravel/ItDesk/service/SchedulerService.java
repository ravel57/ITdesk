package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;


@Service
@RequiredArgsConstructor
public class SchedulerService {

	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final UserService userService;
	private final SessionService sessionService;

	Logger logger = LoggerFactory.getLogger(this.getClass());


	@Scheduled(cron = "*/1 * * * * *")
	void updateClientsInfo() throws ExecutionException, InterruptedException {
		Executors.newSingleThreadExecutor().submit(new AsyncUpdater(clientService, webSocketService, userService, sessionService));
		logger.debug("update & sendClients ClientsInfo");
	}


	private record AsyncUpdater(ClientService clientService,
								WebSocketService webSocketService,
								UserService userService,
								SessionService sessionService) implements Runnable {
		@Override
		public void run() {
			webSocketService.sendClients(clientService.getClients());
			webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
		}
	}

}

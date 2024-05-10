package ru.ravel.ItDesk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;


@Service
public class SchedulerService {
	private final ClientService clientService;
	private final WebSocketService webSocketService;

	Logger logger = LoggerFactory.getLogger(this.getClass());


	public SchedulerService(ClientService clientService, WebSocketService webSocketService) {
		this.clientService = clientService;
		this.webSocketService = webSocketService;
	}


	@Scheduled(cron = "*/1 * * * * *")
	void updateClientsInfo() throws ExecutionException, InterruptedException {
		Executors.newSingleThreadExecutor().submit(new AsyncUpdater(clientService, webSocketService));
		logger.debug("update & sendClients ClientsInfo");
	}


	private record AsyncUpdater(ClientService clientService, WebSocketService webSocketService) implements Runnable {
		@Override
		public void run() {
			webSocketService.sendClients(clientService.getClients());
		}
	}

}

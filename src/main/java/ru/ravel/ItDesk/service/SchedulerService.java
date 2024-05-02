package ru.ravel.ItDesk.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


@Service
public class SchedulerService {

	Logger logger = LoggerFactory.getLogger(this.getClass());

	private final ClientService clientService;

	private final WebSocketService webSocketService;

	public SchedulerService(ClientService clientService, WebSocketService webSocketService) {
		this.clientService = clientService;
		this.webSocketService = webSocketService;
	}

	@Scheduled(cron = "*/1 * * * * *")
	void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		logger.debug("update & sendClients ClientsInfo");
	}

}

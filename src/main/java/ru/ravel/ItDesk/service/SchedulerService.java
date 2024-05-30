package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class SchedulerService {

	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final UserService userService;
	private final EmailService emailService;

	Logger logger = LoggerFactory.getLogger(this.getClass());


	@Scheduled(fixedRate = 500)
	void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
	}

	@Scheduled(fixedRate = 60_000)
	void receiveEmails(){
		emailService.receiveEmails();
	}

}

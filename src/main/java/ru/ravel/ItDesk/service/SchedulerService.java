package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
public class SchedulerService {

	private final ClientService clientService;
	private final WebSocketService webSocketService;
	private final UserService userService;


	@Scheduled(fixedRate = 500)
	void updateClientsInfo() {
		webSocketService.sendClients(clientService.getClients());
		webSocketService.getAuthenticatedUsers(userService.getUsersOnline());
	}

}

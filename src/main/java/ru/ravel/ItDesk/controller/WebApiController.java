package ru.ravel.ItDesk.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.models.Message;
import ru.ravel.ItDesk.models.Task;
import ru.ravel.ItDesk.service.ClientService;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin
public class WebApiController {


	private final ClientService clientService;

	public WebApiController(ClientService clientService) {
		this.clientService = clientService;
	}

	@GetMapping("/clients")
	public ResponseEntity<Object> getClients() {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.getClients());
	}


	@PostMapping("/client/{clientId}/new-task")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.newTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/update-task")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.updateTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/new-message")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.newMessage(clientId, message));
	}

}

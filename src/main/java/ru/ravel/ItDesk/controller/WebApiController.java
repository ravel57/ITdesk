package ru.ravel.ItDesk.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.model.Message;
import ru.ravel.ItDesk.model.Task;
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


	@PostMapping("/client/{clientId}/mark-read")
	public ResponseEntity<Object> markRead(@PathVariable Long clientId) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.markRead(clientId));
	}
}
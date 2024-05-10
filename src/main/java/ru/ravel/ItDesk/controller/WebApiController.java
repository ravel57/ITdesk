package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.service.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
public class WebApiController {

	private final ClientService clientService;
	private final TaskFilterService taskFilterService;
	private final TagService tagService;
	private final OrganizationService organizationService;
	private final UserService userService;
	private final TelegramService telegramService;
	private final StatusService statusService;


	@GetMapping("/clients")
	public ResponseEntity<Object> getClients() {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.getClients());
	}


	@PostMapping("/client/{clientId}/new-task")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Map<String, Object> map) { // FIXME
		return ResponseEntity.status(HttpStatus.OK).body(clientService.newTask(clientId, map));
	}


	@PostMapping("/client/{clientId}/update-task")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.updateTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/new-message")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.newMessage(clientId, message));
	}


	@PostMapping("/client/{clientId}/update")
	public ResponseEntity<Object> updateClient(@PathVariable Long clientId, @RequestBody Map<String, Object> client) {
		return ResponseEntity.status(HttpStatus.OK).body(clientService.updateClient(clientId, client));
	}


	@GetMapping("/filters")
	public ResponseEntity<Object> getFilters() {
		return ResponseEntity.ok().body(taskFilterService.getAll());
	}


	@PostMapping("/new-filter")
	public ResponseEntity<Object> saveTaskFilter(@RequestBody TaskFilter taskFilter) {
		return ResponseEntity.status(HttpStatus.OK).body(taskFilterService.saveTaskFilter(taskFilter));
	}


	@GetMapping("/tags")
	public ResponseEntity<Object> getTags() {
		return ResponseEntity.status(HttpStatus.OK).body(tagService.getTags());
	}


	@GetMapping("/organizations")
	public ResponseEntity<Object> getOrganizations() {
		return ResponseEntity.status(HttpStatus.OK).body(organizationService.getOrganizations());
	}


	@GetMapping("/users")
	public ResponseEntity<Object> getUsers() {
		return ResponseEntity.status(HttpStatus.OK).body(userService.getUsers());
	}


	@GetMapping("/roles")
	public ResponseEntity<Object> getRoles() {
		return ResponseEntity.status(HttpStatus.OK).body(userService.getRoles());
	}


	@GetMapping("/statuses")
	public ResponseEntity<Object> getStatuses() {
		return ResponseEntity.status(HttpStatus.OK).body(statusService.getStatuses());
	}


	@PostMapping("/new-user")
	public ResponseEntity<Object> newUser(@RequestBody FrontendUser user) {
		return ResponseEntity.status(HttpStatus.OK).body(userService.newUser(user));
	}


	@PostMapping("/new-tag")
	public ResponseEntity<Object> newTag(@RequestBody Tag tag) {
		return ResponseEntity.status(HttpStatus.OK).body(tagService.newTag(tag));
	}


	@PostMapping("/new-organization")
	public ResponseEntity<Object> newOrganization(@RequestBody Organization organization) {
		return ResponseEntity.status(HttpStatus.OK).body(organizationService.newOrganization(organization));
	}


	@PostMapping("/get-logged-user")
	public ResponseEntity<Object> getLoggedUser() {
		return ResponseEntity.status(HttpStatus.OK).body(null);
	}


	@GetMapping("/telegram-bots")
	public ResponseEntity<Object> getTelegramBots() {
		return ResponseEntity.status(HttpStatus.OK).body(telegramService.getTelegramBots());
	}


	@PostMapping("/new-telegram-bot")
	public ResponseEntity<Object> newTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.status(HttpStatus.OK).body(telegramService.newTelegramBot(telegramBot));
	}

}
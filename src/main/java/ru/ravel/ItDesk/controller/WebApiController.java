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
		return ResponseEntity.ok().body(clientService.getClients());
	}


	@PostMapping("/client/{clientId}/new-task")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Task task) { // FIXME
		return ResponseEntity.ok().body(clientService.newTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/update-task")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.ok().body(clientService.updateTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/new-message")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		return ResponseEntity.ok().body(clientService.newMessage(clientId, message));
	}


	@PostMapping("/client/{clientId}/update-client")
	public ResponseEntity<Object> updateClient(@PathVariable Long clientId, @RequestBody Map<String, Object> client) {
		return ResponseEntity.ok().body(clientService.updateClient(clientId, client));
	}


	@GetMapping("/filters")
	public ResponseEntity<Object> getFilters() {
		return ResponseEntity.ok().body(taskFilterService.getAll());
	}


	@PostMapping("/new-filter")
	public ResponseEntity<Object> saveTaskFilter(@RequestBody TaskFilter taskFilter) {
		return ResponseEntity.ok().body(taskFilterService.saveTaskFilter(taskFilter));
	}


	@GetMapping("/tags")
	public ResponseEntity<Object> getTags() {
		return ResponseEntity.ok().body(tagService.getTags());
	}


	@GetMapping("/organizations")
	public ResponseEntity<Object> getOrganizations() {
		return ResponseEntity.ok().body(organizationService.getOrganizations());
	}


	@GetMapping("/users")
	public ResponseEntity<Object> getUsers() {
		return ResponseEntity.ok().body(userService.getUsers());
	}


	@GetMapping("/roles")
	public ResponseEntity<Object> getRoles() {
		return ResponseEntity.ok().body(userService.getRoles());
	}


	@GetMapping("/statuses")
	public ResponseEntity<Object> getStatuses() {
		return ResponseEntity.ok().body(statusService.getStatuses());
	}


	@GetMapping("/telegram-bots")
	public ResponseEntity<Object> getTelegramBots() {
		return ResponseEntity.ok().body(telegramService.getTelegramBots());
	}


	@PostMapping("/new-user")
	public ResponseEntity<Object> newUser(@RequestBody FrontendUser user) {
		return ResponseEntity.ok().body(userService.newUser(user));
	}


	@PostMapping("/update-user")
	public ResponseEntity<Object> updateUser(@RequestBody FrontendUser user) {
		return ResponseEntity.ok().body(userService.updateUser(user));
	}


	@PostMapping("/new-tag")
	public ResponseEntity<Object> newTag(@RequestBody Tag tag) {
		return ResponseEntity.ok().body(tagService.newTag(tag));
	}


	@PostMapping("/new-organization")
	public ResponseEntity<Object> newOrganization(@RequestBody Organization organization) {
		return ResponseEntity.ok().body(organizationService.newOrganization(organization));
	}


	@PostMapping("/update-organization")
	public ResponseEntity<Object> updateOrganization(@RequestBody Organization organization) {
		return ResponseEntity.ok().body(organizationService.updateOrganization(organization));
	}


//	@GetMapping("/get-logged-user")
//	public ResponseEntity<Object> getLoggedUser() {
//		return ResponseEntity.ok().body(null);
//	}


	@PostMapping("/new-telegram-bot")
	public ResponseEntity<Object> newTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.ok().body(telegramService.newTelegramBot(telegramBot));
	}


	@PostMapping("/update-telegram-bot")
	public ResponseEntity<Object> updateTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.ok().body(telegramService.updateTelegramBot(telegramBot));
	}


	@PostMapping("/delete-telegram-bot")
	public ResponseEntity<Object> deleteTelegramBot(@RequestBody TgBot telegramBot) {
		telegramService.deleteTelegramBot(telegramBot);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("/new-status")
	public ResponseEntity<Object> newStatus(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.newStatus(status));
	}

}
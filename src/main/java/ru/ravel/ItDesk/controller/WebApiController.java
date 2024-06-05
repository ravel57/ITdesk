package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.dto.MessageTask;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.service.*;

import java.util.List;
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
	private final PriorityService priorityService;
	private final TemplateService templateService;
	private final EmailService emailService;


	@GetMapping("/clients")
	public ResponseEntity<Object> getClients() {
		return ResponseEntity.ok().body(clientService.getClients());
	}


	@PostMapping("/client/{clientId}/new-task")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.ok().body(clientService.newTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/update-task")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.ok().body(clientService.updateTask(task));
	}


	@PostMapping("/client/{clientId}/new-message")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		boolean isMessageDelivered = clientService.sendMessage(clientId, message);
		if (isMessageDelivered) {
			return ResponseEntity.ok().body(true);
		} else {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
	}


	@PostMapping("/client/{clientId}/update-client")
	public ResponseEntity<Object> updateClient(@PathVariable Long clientId, @RequestBody Map<String, Object> client) {
		return ResponseEntity.ok().body(clientService.updateClient(clientId, client));
	}


	@PostMapping("/client/{clientId}/link-message-to-task")
	public ResponseEntity<Object> linkMessageToTask(@PathVariable Long clientId, @RequestBody MessageTask messageTask) {
		return ResponseEntity.ok().body(clientService.linkToTask(messageTask));
	}


	@DeleteMapping("/client/{clientId}/delete-message/{messageId}")
	public ResponseEntity<Object> deleteMessage(@PathVariable Long clientId, @PathVariable Long messageId) {
		boolean isDeleted = clientService.deleteMessage(clientId, messageId);
		if (isDeleted) {
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
	}


	@DeleteMapping("/client/{clientId}")
	public ResponseEntity<Object> deleteClient(@PathVariable Long clientId) {
		boolean isDeleted = clientService.deleteClient(clientId);
		if (isDeleted) {
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
	}


	@GetMapping("/filters")
	public ResponseEntity<Object> getFilters() {
		return ResponseEntity.ok().body(taskFilterService.getAll());
	}


	@PostMapping("/new-filter")
	public ResponseEntity<Object> saveTaskFilter(@RequestBody TaskFilter taskFilter) {
		return ResponseEntity.ok().body(taskFilterService.saveTaskFilter(taskFilter));
	}


	@DeleteMapping("/filter/{filterId}")
	public ResponseEntity<Object> deleteTaskFilter(@PathVariable Long filterId) {
		taskFilterService.deleteTaskFilter(filterId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/users")
	public ResponseEntity<Object> getUsers() {
		return ResponseEntity.ok().body(userService.getUsers());
	}


	@PostMapping("/user/change-password")
	public ResponseEntity<Object> changePassword(@RequestBody Password password) {
		return ResponseEntity.ok().body(userService.changePassword(password));
	}


	@GetMapping("/roles")
	public ResponseEntity<Object> getRoles() {
		return ResponseEntity.ok().body(userService.getRoles());
	}


	@PostMapping("/new-user")
	public ResponseEntity<Object> newUser(@RequestBody FrontendUser user) {
		try {
			return ResponseEntity.ok().body(userService.newUser(user));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
	}


	@PostMapping("/update-user")
	public ResponseEntity<Object> updateUser(@RequestBody FrontendUser user) {
		return ResponseEntity.ok().body(userService.updateUser(user));
	}


	@DeleteMapping("/delete-user/{userId}")
	public ResponseEntity<Object> updateUser(@PathVariable Long userId) {
		userService.deleteUser(userId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("/user-online")
	ResponseEntity<Object> userOnline() {
		return ResponseEntity.ok(userService.userOnline());
	}


	@PostMapping("/user-offline")
	ResponseEntity<Object> userOffline(@RequestBody User user) {
		userService.userOffline(user);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/tags")
	public ResponseEntity<Object> getTags() {
		return ResponseEntity.ok().body(tagService.getTags());
	}


	@PostMapping("/new-tag")
	public ResponseEntity<Object> newTag(@RequestBody Tag tag) {
		return ResponseEntity.ok().body(tagService.newTag(tag));
	}


	@PostMapping("/update-tag")
	public ResponseEntity<Object> updateTag(@RequestBody Tag tag) {
		return ResponseEntity.ok().body(tagService.updateTag(tag));
	}


	@DeleteMapping("/tag/{tagId}")
	public ResponseEntity<Object> deleteTag(@PathVariable Long tagId) {
		tagService.deleteTag(tagId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/organizations")
	public ResponseEntity<Object> getOrganizations() {
		return ResponseEntity.ok().body(organizationService.getOrganizations());
	}


	@PostMapping("/new-organization")
	public ResponseEntity<Object> newOrganization(@RequestBody Organization organization) {
		return ResponseEntity.ok().body(organizationService.newOrganization(organization));
	}


	@PostMapping("/update-organization")
	public ResponseEntity<Object> updateOrganization(@RequestBody Organization organization) {
		return ResponseEntity.ok().body(organizationService.updateOrganization(organization));
	}


	@DeleteMapping("/organization/{organizationId}")
	public ResponseEntity<Object> deleteOrganization(@PathVariable Long organizationId) {
		organizationService.deleteOrganization(organizationId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/get-logged-user")
	public ResponseEntity<Object> getLoggedUser() {
		return ResponseEntity.ok().body(userService.getUsersOnline());
	}


	@GetMapping("/telegram-bots")
	public ResponseEntity<Object> getTelegramBots() {
		return ResponseEntity.ok().body(telegramService.getTelegramBots());
	}


	@PostMapping("/new-telegram-bot")
	public ResponseEntity<Object> newTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.ok().body(telegramService.newTelegramBot(telegramBot));
	}


	@PostMapping("/update-telegram-bot")
	public ResponseEntity<Object> updateTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.ok().body(telegramService.updateTelegramBot(telegramBot));
	}


	@DeleteMapping("/telegram-bot/{tgBotId}")
	public ResponseEntity<Object> deleteTelegramBot(@PathVariable Long tgBotId) {
		telegramService.deleteTelegramBot(tgBotId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/emails")
	public ResponseEntity<Object> getEmailAccounts() {
		return ResponseEntity.ok().body(emailService.getEmailsAccounts());
	}


	@PostMapping("/new-email")
	public ResponseEntity<Object> newEmailAccount(@RequestBody EmailAccount emailAccount) {
		return ResponseEntity.ok().body(emailService.newEmailAccount(emailAccount));
	}


	@PostMapping("/update-email")
	public ResponseEntity<Object> updateEmailAccount(@RequestBody EmailAccount emailAccount) {
		return ResponseEntity.ok().body(emailService.updateEmailAccount(emailAccount));
	}


	@DeleteMapping("/email/{emailAccountId}")
	public ResponseEntity<Object> deleteEmailAccount(@PathVariable Long emailAccountId) {
		emailService.deleteEmailAccount(emailAccountId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("/new-status")
	public ResponseEntity<Object> newStatus(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.newStatus(status));
	}


	@PostMapping("/update-status")
	public ResponseEntity<Object> updateStatus(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.updateStatus(status));
	}


	@DeleteMapping("/status/{statusId}")
	public ResponseEntity<Object> deleteStatus(@PathVariable Long statusId) {
		statusService.deleteStatus(statusId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("/new-priority")
	public ResponseEntity<Object> newPriority(@RequestBody Priority priority) {
		return ResponseEntity.ok().body(priorityService.newPriority(priority));
	}


	@DeleteMapping("/priority/{priorityId}")
	public ResponseEntity<Object> deletePriority(@PathVariable Long priorityId) {
		priorityService.deletePriority(priorityId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/templates")
	public ResponseEntity<Object> getTemplates() {
		return ResponseEntity.ok().body(templateService.getTemplates());
	}


	@PostMapping("/new-template")
	public ResponseEntity<Object> newTemplate(@RequestBody Template template) {
		return ResponseEntity.ok().body(templateService.newTemplate(template));
	}


	@PostMapping("/update-template")
	public ResponseEntity<Object> updateTemplate(@RequestBody Template template) {
		return ResponseEntity.ok().body(templateService.updateTemplate(template));
	}


	@DeleteMapping("/template/{templateId}")
	public ResponseEntity<Object> deleteTemplate(@PathVariable Long templateId) {
		templateService.deleteTemplate(templateId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("/update-status/set-default")
	public ResponseEntity<Object> statusSetDefaultSelection(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.statusSetDefaultSelection(status));
	}


	@PostMapping("/update-priority/set-default")
	public ResponseEntity<Object> statusPriorityDefaultSelection(@RequestBody Priority priority) {
		return ResponseEntity.ok().body(priorityService.statusSetDefaultSelection(priority));
	}


	@PostMapping("/get-authenticated-users")
	public ResponseEntity<Object> getAllAuthenticatedUsers() {
		return ResponseEntity.ok().body(userService.getUsersOnline());
	}


	@GetMapping("/statuses")
	public ResponseEntity<Object> getStatuses() {
		return ResponseEntity.ok().body(statusService.getStatuses());
	}


	@PostMapping("/update-status/resort")
	public ResponseEntity<Object> resortStatuses(@RequestBody List<Status> statuses) {
		return ResponseEntity.ok().body(statusService.resortStatuses(statuses));
	}


	@PostMapping("/update-templates/resort")
	public ResponseEntity<Object> resortTemplates(@RequestBody List<Template> templates) {
		return ResponseEntity.ok().body(templateService.resortTemplates(templates));
	}


	@PostMapping("/update-tags/resort")
	public ResponseEntity<Object> resortTags(@RequestBody List<Tag> tags) {
		return ResponseEntity.ok().body(tagService.resortTags(tags));
	}


	@GetMapping("/priorities")
	public ResponseEntity<Object> getPriorities() {
		return ResponseEntity.ok().body(priorityService.getPriorities());
	}


	@PostMapping("/update-priorities/resort")
	public ResponseEntity<Object> resortPriorities(@RequestBody List<Priority> priorities) {
		return ResponseEntity.ok().body(priorityService.resortPriorities(priorities));
	}

}
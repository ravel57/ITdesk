package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.dto.MessageTask;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.service.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
	private final KnowledgeService knowledgeService;


	@GetMapping("/clients")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getClients() {
		return ResponseEntity.ok().body(clientService.getClients());
	}


	@PostMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.ok().body(clientService.newTask(clientId, task));
	}


	@PatchMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		return ResponseEntity.ok().body(clientService.updateTask(clientId, task));
	}


	@PostMapping("/client/{clientId}/task/{taskId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> addTaskMessage(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody Message message) {
		boolean isSuccess = clientService.addTaskMessage(taskId, message);
		if (isSuccess) {
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
	}


	@PostMapping("/client/{clientId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		boolean isMessageDelivered = clientService.sendMessage(clientId, message);
		if (isMessageDelivered) {
			return ResponseEntity.ok().body(true);
		} else {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
	}


	@PatchMapping("/client/{clientId}/client")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> updateClient(@PathVariable Long clientId, @RequestBody Map<String, Object> client) {
		return ResponseEntity.ok().body(clientService.updateClient(clientId, client));
	}


	@PostMapping("/client/{clientId}/link-message-to-task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> linkMessageToTask(@PathVariable Long clientId, @RequestBody MessageTask messageTask) {
		return ResponseEntity.ok().body(clientService.linkToTask(messageTask));
	}


	@DeleteMapping("/client/{clientId}/delete-message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteMessage(@PathVariable Long clientId, @PathVariable Long messageId) {
		boolean isDeleted = clientService.deleteMessage(clientId, messageId);
		if (isDeleted) {
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
		}
	}


	@PreAuthorize("hasAnyRole('ADMIN')")
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
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getFilters() {
		return ResponseEntity.ok().body(taskFilterService.getAll());
	}


	@PostMapping("/filter")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> saveTaskFilter(@RequestBody TaskFilter taskFilter) {
		return ResponseEntity.ok().body(taskFilterService.saveTaskFilter(taskFilter));
	}


	@DeleteMapping("/filter/{filterId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTaskFilter(@PathVariable Long filterId) {
		taskFilterService.deleteTaskFilter(filterId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/users")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getUsers() {
		return ResponseEntity.ok().body(userService.getUsers());
	}


	@PostMapping("/user/change-password")
	public ResponseEntity<Object> changePassword(@RequestBody Password password) {
		return ResponseEntity.ok().body(userService.changePassword(password));
	}


	@GetMapping("/roles")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> getRoles() {
		return ResponseEntity.ok().body(userService.getRoles());
	}


	@PostMapping("/user")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newUser(@RequestBody FrontendUser user) {
		try {
			return ResponseEntity.ok().body(userService.newUser(user));
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
	}


	@PatchMapping("/user")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateUser(@RequestBody FrontendUser user) {
		return ResponseEntity.ok().body(userService.updateUser(user));
	}


	@DeleteMapping("/delete-user/{userId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
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
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTags() {
		return ResponseEntity.ok().body(tagService.getTags());
	}


	@PostMapping("/tag")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTag(@RequestBody Tag tag) {
		return ResponseEntity.ok().body(tagService.newTag(tag));
	}


	@PatchMapping("/tag")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTag(@RequestBody Tag tag) {
		return ResponseEntity.ok().body(tagService.updateTag(tag));
	}


	@DeleteMapping("/tag/{tagId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTag(@PathVariable Long tagId) {
		tagService.deleteTag(tagId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/organizations")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getOrganizations() {
		return ResponseEntity.ok().body(organizationService.getOrganizations());
	}


	@PostMapping("/organization")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newOrganization(@RequestBody Organization organization) {
		return ResponseEntity.ok().body(organizationService.newOrganization(organization));
	}


	@PatchMapping("/organization")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateOrganization(@RequestBody Organization organization) {
		return ResponseEntity.ok().body(organizationService.updateOrganization(organization));
	}


	@DeleteMapping("/organization/{organizationId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteOrganization(@PathVariable Long organizationId) {
		organizationService.deleteOrganization(organizationId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/get-logged-user")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getLoggedUser() {
		return ResponseEntity.ok().body(userService.getUsersOnline());
	}


	@GetMapping("/telegram-bots")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTelegramBots() {
		return ResponseEntity.ok().body(telegramService.getTelegramBots());
	}


	@PostMapping("/telegram-bot")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.ok().body(telegramService.newTelegramBot(telegramBot));
	}


	@PatchMapping("/telegram-bot")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTelegramBot(@RequestBody TgBot telegramBot) {
		return ResponseEntity.ok().body(telegramService.updateTelegramBot(telegramBot));
	}


	@DeleteMapping("/telegram-bot/{tgBotId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTelegramBot(@PathVariable Long tgBotId) {
		telegramService.deleteTelegramBot(tgBotId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/emails")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getEmailAccounts() {
		return ResponseEntity.ok().body(emailService.getEmailsAccounts());
	}


	@PostMapping("/email")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newEmailAccount(@RequestBody EmailAccount emailAccount) {
		return ResponseEntity.ok().body(emailService.newEmailAccount(emailAccount));
	}


	@PatchMapping("/email")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateEmailAccount(@RequestBody EmailAccount emailAccount) {
		return ResponseEntity.ok().body(emailService.updateEmailAccount(emailAccount));
	}


	@DeleteMapping("/email/{emailAccountId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteEmailAccount(@PathVariable Long emailAccountId) {
		emailService.deleteEmailAccount(emailAccountId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("/status")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newStatus(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.newStatus(status));
	}


	@PatchMapping("/status")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateStatus(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.updateStatus(status));
	}


	@DeleteMapping("/status/{statusId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteStatus(@PathVariable Long statusId) {
		statusService.deleteStatus(statusId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@GetMapping("/priorities")
	public ResponseEntity<Object> getPriorities() {
		return ResponseEntity.ok().body(priorityService.getPriorities());
	}


	@PostMapping("/priority")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newPriority(@RequestBody Priority priority) {
		return ResponseEntity.ok().body(priorityService.newPriority(priority));
	}


	@PatchMapping("/priority")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updatePriority(@RequestBody Priority priority) {
		return ResponseEntity.ok().body(priorityService.updatePriority(priority));
	}


	@DeleteMapping("/priority/{priorityId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deletePriority(@PathVariable Long priorityId) {
		priorityService.deletePriority(priorityId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PatchMapping("/priority/set-default")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> prioritySetDefaultSelection(@RequestBody Priority priority) {
		return ResponseEntity.ok().body(priorityService.prioritySetDefaultSelection(priority));
	}


	@PatchMapping("/priority/set-high-priority")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> prioritySetCritical(@RequestBody Priority priority) {
		return ResponseEntity.ok().body(priorityService.prioritySetCritical(priority));
	}


	@PatchMapping("/priorities/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortPriorities(@RequestBody List<Priority> priorities) {
		return ResponseEntity.ok().body(priorityService.resortPriorities(priorities));
	}


	@GetMapping("/templates")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTemplates() {
		return ResponseEntity.ok().body(templateService.getTemplates());
	}


	@PostMapping("/template")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTemplate(@RequestBody Template template) {
		return ResponseEntity.ok().body(templateService.newTemplate(template));
	}


	@PatchMapping("/template")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTemplate(@RequestBody Template template) {
		return ResponseEntity.ok().body(templateService.updateTemplate(template));
	}


	@DeleteMapping("/template/{templateId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTemplate(@PathVariable Long templateId) {
		templateService.deleteTemplate(templateId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PatchMapping("/status/set-default")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> statusSetDefaultSelection(@RequestBody Status status) {
		return ResponseEntity.ok().body(statusService.statusSetDefaultSelection(status));
	}


	@PostMapping("/get-authenticated-users")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getAllAuthenticatedUsers() {
		return ResponseEntity.ok().body(userService.getUsersOnline());
	}


	@GetMapping("/statuses")
	public ResponseEntity<Object> getStatuses() {
		return ResponseEntity.ok().body(statusService.getStatuses());
	}


	@PatchMapping("/status/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortStatuses(@RequestBody List<Status> statuses) {
		return ResponseEntity.ok().body(statusService.resortStatuses(statuses));
	}


	@PatchMapping("/templates/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortTemplates(@RequestBody List<Template> templates) {
		return ResponseEntity.ok().body(templateService.resortTemplates(templates));
	}


	@PatchMapping("/tags/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortTags(@RequestBody List<Tag> tags) {
		return ResponseEntity.ok().body(tagService.resortTags(tags));
	}


	@GetMapping("/sla")
	public ResponseEntity<Object> getSlaByPriority() {
		HashMap<Object, Object> out = new HashMap<>();
		Map<Organization, Map<Priority, Duration>> slaByPriority = organizationService.getSlaByPriority();
		for (Organization organization : slaByPriority.keySet()) {
			Map<String, Duration> collect = slaByPriority.get(organization).entrySet().stream()
					.collect(Collectors.toMap(e -> e.getKey().getName(), e -> Objects.requireNonNullElse(e.getValue(), Duration.ZERO)));
			out.put(organization.getName(), collect);
		}
		return ResponseEntity.ok().body(out);
	}


	@PostMapping("/sla")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> postSlaByPriority(@RequestBody OrganizationPriorityDuration slaByPriority) {
		organizationService.setSlaByPriority(slaByPriority);
		return ResponseEntity.ok().build();
	}


	@GetMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getKnowledgeBase() {
		return ResponseEntity.ok().body(knowledgeService.getKnowledgeBase());
	}


	@PostMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newKnowledge(@RequestBody Knowledge knowledge) {
		return ResponseEntity.ok().body(knowledgeService.newKnowledge(knowledge));
	}


	@PatchMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateKnowledge(@RequestBody Knowledge knowledge) {
		return ResponseEntity.ok().body(knowledgeService.updateKnowledge(knowledge));
	}


	@DeleteMapping("knowledge-base/{knowledgeId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteKnowledge(@PathVariable Long knowledgeId) {
		knowledgeService.deleteKnowledge(knowledgeId);
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@PostMapping("knowledge-base/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortKnowledgeBase(@RequestBody List<Knowledge> knowledge) {
		return ResponseEntity.ok().body(knowledgeService.resortKnowledge(knowledge));
	}

}
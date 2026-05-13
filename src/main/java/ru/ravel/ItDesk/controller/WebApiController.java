package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.TaskRepository;
import ru.ravel.ItDesk.service.*;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.*;

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
	private final WhatsappService whatsappService;
	private final ExportService exportService;
	private final LlmService llmService;
	private final AutomationTriggerService automationTriggerService;
	private final SlaService slaService;
	private final TaskRepository taskRepository;    // TODO remove from here
	private final AnalyticsService analyticsService;
	private final GlobalSearchService globalSearchService;
	private final TaskHistoryService taskHistoryService;
	private final TaskService taskService;
	private final WebSocketService webSocketService;


	@GetMapping("/clients")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getClients() {
		return ResponseEntity.ok().body(clientService.getClients());
	}


	@PostMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Task task) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(taskService.newTask(clientId, task));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(taskService.updateTask(clientId, task));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/client-files/{clientId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getClientFiles(@PathVariable Long clientId) {
		return ResponseEntity.ok().body(clientService.getClientFiles(clientId));
	}


	@PostMapping("/client/{clientId}/task/{taskId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> addTaskMessage(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody Message message) {
		if (LicenseStarter.isLicenseActive) {
			try {
				Message savedMessage = clientService.addTaskMessage(taskId, message);
				webSocketService.taskMessage(clientId, taskId, savedMessage);
				return ResponseEntity.ok(savedMessage);
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.CONFLICT).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/client/{clientId}/task/{taskId}/message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> editTaskMessage(
			@PathVariable Long clientId,
			@PathVariable Long taskId,
			@PathVariable Long messageId,
			@RequestBody Message message
	) {
		if (LicenseStarter.isLicenseActive) {
			try {
				Message savedMessage = clientService.editTaskMessage(clientId, taskId, messageId, message);
				webSocketService.taskMessage(clientId, taskId, savedMessage);
				return ResponseEntity.ok(savedMessage);
			} catch (IllegalArgumentException e) {
				return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/client/{clientId}/task/{taskId}/mark-message-read")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> markMessageRead(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody UserId userId) {
		if (LicenseStarter.isLicenseActive) {
			clientService.markMessageRead(taskId, userId);
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/client/{clientId}/task/{taskId}/search-messages")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> searchTaskMessage(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody MessageText messageText) {
		return ResponseEntity.ok().body(clientService.searchTaskMessage(taskId, messageText));
	}


	@PostMapping("/client/{clientId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		if (LicenseStarter.isLicenseActive) {
			boolean isMessageDelivered = clientService.sendMessage(clientId, message);
			if (isMessageDelivered) {
				return ResponseEntity.ok().body(true);
			} else {
				return ResponseEntity.status(HttpStatus.CONFLICT).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/client/{clientId}/message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> editMessage(
			@PathVariable Long clientId,
			@PathVariable Long messageId,
			@RequestBody Message message
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(clientService.editMessage(clientId, messageId, message));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (Exception e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
		}
	}


	@PatchMapping("/client/{clientId}/message/{messageId}/answer-required")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> answerRequired(
			@PathVariable Long clientId,
			@PathVariable Long messageId,
			@RequestBody AnswerRequired answerRequired
	) {
		if (LicenseStarter.isLicenseActive) {
			clientService.answerRequired(clientId, messageId, answerRequired);
			return ResponseEntity.ok().body(true);
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/client/{clientId}/client")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> updateClient(@PathVariable Long clientId, @RequestBody Map<String, Object> client) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(clientService.updateClient(clientId, client));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/client/{clientId}/link-message-to-task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> linkMessageToTask(@PathVariable Long clientId, @RequestBody MessageTask messageTask) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(clientService.linkToTask(messageTask));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/client/{clientId}/linked-message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getLinkedMessage(@PathVariable Long clientId, @RequestParam Long linkedMessageId) {
		return ResponseEntity.ok().body(clientService.getMessagesUntilLinkedMessage(clientId, linkedMessageId));
	}


	@DeleteMapping("/client/{clientId}/delete-message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteMessage(@PathVariable Long clientId, @PathVariable Long messageId) {
		if (LicenseStarter.isLicenseActive) {
			boolean isDeleted = clientService.deleteMessage(clientId, messageId);
			if (isDeleted) {
				return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
			} else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/client/{clientId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteClient(@PathVariable Long clientId) {
		if (LicenseStarter.isLicenseActive) {
			boolean isDeleted = clientService.deleteClient(clientId);
			if (isDeleted) {
				return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
			} else {
				return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/client/{clientId}/messages-page")
	public ResponseEntity<Object> getPageOfMessages(@PathVariable Long clientId, @RequestParam Integer page) {
		return ResponseEntity.ok().body(clientService.getPageOfMessages(clientId, page));
	}


	@PostMapping("/client/{clientId}/search-messages")
	public ResponseEntity<Object> searchMessages(@PathVariable Long clientId, @RequestBody MessageText messageText) {
		return ResponseEntity.ok().body(clientService.searchMessages(clientId, messageText));
	}


	@GetMapping("/filters")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getFilters() {
		return ResponseEntity.ok().body(taskFilterService.getAll());
	}


	@PostMapping("/filter")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> saveTaskFilter(@RequestBody TaskFilter taskFilter) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(taskFilterService.saveTaskFilter(taskFilter));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/filter/{filterId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTaskFilter(@PathVariable Long filterId) {
		if (LicenseStarter.isLicenseActive) {
			taskFilterService.deleteTaskFilter(filterId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
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
	public ResponseEntity<Object> newUser(@RequestBody UserDto user) {
		if (LicenseStarter.isLicenseActive) {
			try {
				return ResponseEntity.ok().body(userService.newUser(user));
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.CONFLICT).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/user")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateUser(@RequestBody UserDto user) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(userService.updateUser(user));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/delete-user/{userId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateUser(@PathVariable Long userId) {
		if (LicenseStarter.isLicenseActive) {
			userService.deleteUser(userId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/user-online")
	public ResponseEntity<Object> userOnline() {
		return ResponseEntity.ok(userService.userOnline());
	}


	@PostMapping("/user-offline")
	public ResponseEntity<Object> userOffline(@RequestBody User user) {
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
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(tagService.newTag(tag));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/tag")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTag(@RequestBody Tag tag) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(tagService.updateTag(tag));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/tag/{tagId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTag(@PathVariable Long tagId) {
		if (LicenseStarter.isLicenseActive) {
			tagService.deleteTag(tagId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/organizations")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getOrganizations() {
		return ResponseEntity.ok().body(organizationService.getOrganizations());
	}


	@PostMapping("/organization")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newOrganization(@RequestBody Organization organization) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(organizationService.newOrganization(organization));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/organization")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateOrganization(@RequestBody Organization organization) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(organizationService.updateOrganization(organization));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/organization/{organizationId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteOrganization(@PathVariable Long organizationId) {
		if (LicenseStarter.isLicenseActive) {
			organizationService.deleteOrganization(organizationId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/telegram-bots")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTelegramBots() {
		return ResponseEntity.ok().body(telegramService.getTelegramBots());
	}


	@PostMapping("/telegram-bot")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTelegramBot(@RequestBody TgBot telegramBot) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(telegramService.newTelegramBot(telegramBot));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/telegram-bot")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTelegramBot(@RequestBody TgBot telegramBot) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(telegramService.updateTelegramBot(telegramBot));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/telegram-bot/{tgBotId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTelegramBot(@PathVariable Long tgBotId) {
		if (LicenseStarter.isLicenseActive) {
			telegramService.deleteTelegramBot(tgBotId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/emails")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getEmailAccounts() {
		return ResponseEntity.ok().body(emailService.getEmailsAccounts());
	}


	@PostMapping("/email")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newEmailAccount(@RequestBody EmailAccount emailAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(emailService.newEmailAccount(emailAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/email")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateEmailAccount(@RequestBody EmailAccount emailAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(emailService.updateEmailAccount(emailAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/email/{emailAccountId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteEmailAccount(@PathVariable Long emailAccountId) {
		if (LicenseStarter.isLicenseActive) {
			emailService.deleteEmailAccount(emailAccountId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/whatsapp")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getWhatsappAccounts() {
		return ResponseEntity.ok().body(whatsappService.getWhatsappAccounts());
	}


	@PostMapping("/whatsapp")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newWhatsappAccount(@RequestBody WhatsappAccount whatsappAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(whatsappService.newWhatsappAccount(whatsappAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/whatsapp")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateWhatsappAccount(@RequestBody WhatsappAccount whatsappAccount) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(whatsappService.updateWhatsappAccount(whatsappAccount));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/whatsapp/{accountId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteWhatsappAccount(@PathVariable Long accountId) {
		if (LicenseStarter.isLicenseActive) {
			whatsappService.deleteWhatsappAccount(accountId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/status")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newStatus(@RequestBody Status status) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(statusService.newStatus(status));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/status")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateStatus(@RequestBody Status status) {
		if (LicenseStarter.isLicenseActive) {
			try {
				return ResponseEntity.ok().body(statusService.updateStatus(status));
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/status/{statusId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteStatus(@PathVariable Long statusId) {
		if (LicenseStarter.isLicenseActive) {
			try {
				statusService.deleteStatus(statusId);
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
			}
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/priorities")
	public ResponseEntity<Object> getPriorities() {
		return ResponseEntity.ok().body(priorityService.getPriorities());
	}


	@PostMapping("/priority")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newPriority(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.newPriority(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/priority")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updatePriority(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.updatePriority(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/priority/{priorityId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deletePriority(@PathVariable Long priorityId) {
		if (LicenseStarter.isLicenseActive) {
			priorityService.deletePriority(priorityId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/priority/set-default")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> prioritySetDefaultSelection(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.prioritySetDefaultSelection(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/priority/set-high-priority")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> prioritySetCritical(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.prioritySetCritical(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/priorities/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortPriorities(@RequestBody List<Priority> priorities) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.resortPriorities(priorities));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/templates")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTemplates() {
		return ResponseEntity.ok().body(templateService.getTemplates());
	}


	@PostMapping("/template")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTemplate(@RequestBody Template template) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(templateService.newTemplate(template));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/template")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTemplate(@RequestBody Template template) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(templateService.updateTemplate(template));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/template/{templateId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTemplate(@PathVariable Long templateId) {
		if (LicenseStarter.isLicenseActive) {
			templateService.deleteTemplate(templateId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/status/set-default")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> statusSetDefaultSelection(@RequestBody Status status) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(statusService.statusSetDefaultSelection(status));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/get-authenticated-users")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getAllAuthenticatedUsers() {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(userService.getUsersOnline());
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/statuses")
	public ResponseEntity<Object> getStatuses() {
		return ResponseEntity.ok().body(statusService.getStatuses());
	}


	@PatchMapping("/status/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortStatuses(@RequestBody List<Status> statuses) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(statusService.resortStatuses(statuses));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/templates/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortTemplates(@RequestBody List<Template> templates) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(templateService.resortTemplates(templates));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/tags/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortTags(@RequestBody List<Tag> tags) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(tagService.resortTags(tags));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/sla")
	public ResponseEntity<Map<String, Map<String, Map<String, Object>>>> getSlaByPriority() {
		Map<String, Map<String, Map<String, Object>>> out = new HashMap<>();
		Map<Organization, Map<Priority, SlaValue>> slaByPriority = organizationService.getSlaByPriority();
		slaByPriority.forEach((organization, priorityMap) -> {
			Map<String, Map<String, Object>> priorities = new HashMap<>();
			priorityMap.forEach((priority, slaValue) -> {
				BigDecimal value = slaValue == null || slaValue.getValue() == null
						? BigDecimal.ZERO
						: slaValue.getValue();
				String unit = slaValue == null || slaValue.getUnit() == null
						? SlaUnit.HOURS.name()
						: slaValue.getUnit().name();
				Map<String, Object> dto = new HashMap<>();
				dto.put("value", value);
				dto.put("unit", unit);
				priorities.put(priority.getName(), dto);
			});
			out.put(organization.getName(), priorities);
		});
		return ResponseEntity.ok(out);
	}


	@PostMapping("/task/{taskId}/sla/pause")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> pauseTaskSla(
			@PathVariable Long taskId,
			@RequestParam(required = false) String reason
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.pauseTaskSla(taskId, reason);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@PostMapping("/task/{taskId}/sla/resume")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> resumeTaskSla(@PathVariable Long taskId) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.resumeTaskSla(taskId);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@GetMapping("/task/{taskId}/sla/info")
	public ResponseEntity<SlaInfoDto> getTaskSlaInfo(@PathVariable Long taskId) {
		Optional<Task> taskOpt = taskRepository.findByIdWithSla(taskId);
		if (taskOpt.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		Sla sla = taskOpt.get().getSla();
		if (sla == null) {
			return ResponseEntity.ok(new SlaInfoDto(false, null, 0L, 0L));
		}
		return ResponseEntity.ok(buildInfo(sla));
	}


	private SlaInfoDto buildInfo(Sla sla) {
		boolean paused = slaService.isPaused(sla);
		ZonedDateTime deadline = slaService.deadline(sla);
		long pausedSeconds = slaService.getPausedDuration(sla).getSeconds();
		long remainingSeconds = slaService.remaining(sla).getSeconds();
		return new SlaInfoDto(paused, deadline, remainingSeconds, pausedSeconds);
	}


	@PostMapping("/sla")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> postSla(@RequestBody OrganizationPriorityDuration sla) {
		if (LicenseStarter.isLicenseActive) {
			organizationService.setSla(sla);
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getKnowledgeBase() {
		return ResponseEntity.ok().body(knowledgeService.getKnowledgeBase());
	}


	@PostMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newKnowledge(@RequestBody Knowledge knowledge) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(knowledgeService.newKnowledge(knowledge));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("knowledge-base")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateKnowledge(@RequestBody Knowledge knowledge) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(knowledgeService.updateKnowledge(knowledge));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("knowledge-base/{knowledgeId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteKnowledge(@PathVariable Long knowledgeId) {
		if (LicenseStarter.isLicenseActive) {
			knowledgeService.deleteKnowledge(knowledgeId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("knowledge-base/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortKnowledgeBase(@RequestBody List<Knowledge> knowledge) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(knowledgeService.resortKnowledge(knowledge));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/triggers")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> getAllTriggers() {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(automationTriggerService.list());
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/trigger")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newTrigger(@RequestBody AutomationTriggerDto dto) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(automationTriggerService.create(dto));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/trigger")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTrigger(@RequestBody AutomationTriggerDto dto) {
		if (LicenseStarter.isLicenseActive) {
			try {
				return ResponseEntity.ok().body(automationTriggerService.update(dto));
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/trigger/{triggerId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTrigger(@PathVariable Long triggerId) {
		if (LicenseStarter.isLicenseActive) {
			try {
				automationTriggerService.delete(triggerId);
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
			}
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/triggers/resort")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> resortTriggers(@RequestBody List<AutomationTriggerDto> triggers) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(automationTriggerService.resort(triggers));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@GetMapping("/trigger-types")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> getTriggerTypes() {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(TriggerType.values());
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/support/send-message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> sendSupportMessage(@RequestBody Message message) {
		return ResponseEntity.ok(userService.sendSupportMessage(message));
	}


	@PostMapping("/support/resave-message")
	public ResponseEntity<Object> resaveMessage(@RequestBody Message message, @RequestParam String license) {
		userService.resaveMessage(license, message);
		return ResponseEntity.ok().build();
	}


	@PostMapping("/support/reset-password")
	public ResponseEntity<Object> resetPassword(@RequestBody Username username) {
		userService.resetPassword(username.getUsername());
		return ResponseEntity.ok().build();
	}


	@GetMapping("/export/to-excel")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<byte[]> exportToExcel() {
		return exportService.exportToExcel();
	}


	@GetMapping("/license-info")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> getLicenseValidUntil() {
		return ResponseEntity.ok().body(new LicenseInfo(LicenseStarter.maxUsers, LicenseStarter.licenseUntil));
	}


	@GetMapping("/llm-query")
	public ResponseEntity<Object> getLlmQuery(@RequestParam String query) {
		return ResponseEntity.ok().body(llmService.askLlm(query));
	}


	@GetMapping("/analytics/summary")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getAnalyticsSummary(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(defaultValue = "DAY") String groupBy
	) {
		return ResponseEntity.ok().body(analyticsService.getSummary(from, to, groupBy));
	}


	@GetMapping("/global-search")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> globalSearch(@RequestParam String query) {
		return ResponseEntity.ok().body(globalSearchService.search(query));
	}


	@PostMapping("/global-search/reindex")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> reindexGlobalSearch() {
		return ResponseEntity.ok().body(globalSearchService.reindexAll());
	}


	@GetMapping("/global-search/count")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> countGlobalSearchDocuments() {
		return ResponseEntity.ok().body(globalSearchService.countDocuments());
	}


	@GetMapping("/task/{taskId}/history")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTaskHistory(@PathVariable Long taskId) {
		return ResponseEntity.ok(taskHistoryService.getTaskHistory(taskId));
	}


	@GetMapping("/task-types")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTaskTypes() {
		return ResponseEntity.ok(taskService.getTaskTypes());
	}


	@GetMapping("/task-types/{id}")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getTaskType(@PathVariable Long id) {
		try {
			return ResponseEntity.ok(taskService.getTaskType(id));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		}
	}


	@PostMapping("/task-types")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> createTaskType(@RequestBody TaskType taskType) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		try {
			return ResponseEntity.ok(taskService.createTaskType(taskType));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
		}
	}


	@PatchMapping("/task-types/{id}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateTaskType(
			@PathVariable Long id,
			@RequestBody TaskType taskType
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}

		try {
			return ResponseEntity.ok(taskService.updateTaskType(id, taskType));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
		}
	}


	@DeleteMapping("/task-types/{id}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteTaskType(@PathVariable Long id) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			taskService.deleteTaskType(id);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		}
	}


	@GetMapping("/user/notification-settings")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getCurrentUserNotificationSettings() {
		return ResponseEntity.ok().body(userService.getCurrentUserNotificationSettings());
	}


	@PatchMapping("/user/notification-settings")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> updateCurrentUserNotificationSettings(@RequestBody UserNotificationSettingsDto dto) {
		return ResponseEntity.ok().body(userService.updateCurrentUserNotificationSettings(dto));
	}


	@PostMapping("/client/{clientId}/task/{taskId}/sla/pause")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> pauseClientTaskSla(@PathVariable Long clientId, @PathVariable Long taskId, @RequestParam(required = false) String reason) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.pauseTaskSla(clientId, taskId, reason);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@PostMapping("/client/{clientId}/task/{taskId}/sla/resume")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> resumeClientTaskSla(@PathVariable Long clientId, @PathVariable Long taskId) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.resumeTaskSla(clientId, taskId);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@GetMapping("/client/{clientId}/task/{taskId}/sla/info")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> getClientTaskSlaInfo(@PathVariable Long clientId, @PathVariable Long taskId) {
		Optional<Task> taskOpt = taskRepository.findByIdWithSla(taskId);
		if (taskOpt.isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		Task task = taskOpt.get();
		if (task.getSla() == null) {
			return ResponseEntity.ok(new SlaInfoDto(false, null, 0L, 0L));
		}
		return ResponseEntity.ok(buildInfo(task.getSla()));
	}

}
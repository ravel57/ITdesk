package ru.ravel.ItDesk.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.MessageTask;
import ru.ravel.ItDesk.dto.OrganizationPriorityDuration;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.dto.UserDto;
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
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(clientService.newTask(clientId, task));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PatchMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(clientService.updateTask(clientId, task));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@PostMapping("/client/{clientId}/task/{taskId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	public ResponseEntity<Object> addTaskMessage(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody Message message) {
		if (LicenseStarter.isLicenseActive) {
			boolean isSuccess = clientService.addTaskMessage(taskId, message);
			if (isSuccess) {
				return ResponseEntity.ok().build();
			} else {
				return ResponseEntity.status(HttpStatus.CONFLICT).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
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


	@GetMapping("/client/{clientId}/get-message-page")
	public ResponseEntity<Object> getPageOfMessages(@PathVariable Long clientId, @RequestParam Integer page) {
		return ResponseEntity.ok().body(clientService.getPageOfMessages(clientId, page));
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
			return ResponseEntity.ok().body(statusService.updateStatus(status));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@DeleteMapping("/status/{statusId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteStatus(@PathVariable Long statusId) {
		if (LicenseStarter.isLicenseActive) {
			statusService.deleteStatus(statusId);
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
	public ResponseEntity<Object> getSlaByPriority() {
		Map<String, Map<String, Long>> out = new HashMap<>();
		Map<Organization, Map<Priority, Duration>> slaByPriority = organizationService.getSlaByPriority();
		slaByPriority.forEach((organization, priorityMap) -> {    // FIXME ошибка сериализаци Duration в json
			Map<String, Long> collect = priorityMap.entrySet().stream()
					.collect(Collectors.toMap(
							e -> e.getKey().getName(),
							e -> Objects.requireNonNullElse(e.getValue(), Duration.ZERO).toHours()));
			out.put(organization.getName(), collect);
		});
		return ResponseEntity.ok().body(out);
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


	@PostMapping("/user/{userId}/support/new-message")
	@PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
	ResponseEntity<Object> sendSupportMessage(@PathVariable Long userId, @RequestBody Message message) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok(userService.sendSupportMessage(userId, message));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}

}
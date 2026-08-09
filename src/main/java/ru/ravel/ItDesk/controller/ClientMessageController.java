package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.*;
import ru.ravel.ItDesk.model.*;
import ru.ravel.ItDesk.model.automatosation.TriggerType;
import ru.ravel.ItDesk.repository.AppSettingsRepository;
import ru.ravel.ItDesk.repository.ClientRepository;
import ru.ravel.ItDesk.service.*;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
@Tag(name = "Сообщения клиентов", description = "Сообщения клиента, сообщения задач, связывание и признаки ответа.")
public class ClientMessageController {

	private final ClientService clientService;
	private final TaskService taskService;
	private final WebSocketService webSocketService;


	@Operation(summary = "Добавить сообщение в заявку", description = "Добавить сообщение в заявку. Endpoint POST /api/v1/client/{clientId}/task/{taskId}/message; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/task/{taskId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
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


	@Operation(summary = "Изменить сообщение заявки", description = "Изменить сообщение заявки. Endpoint PATCH /api/v1/client/{clientId}/task/{taskId}/message/{messageId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/client/{clientId}/task/{taskId}/message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
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


	@Operation(summary = "Отметить сообщение прочитанным", description = "Отметить сообщение прочитанным. Endpoint POST /api/v1/client/{clientId}/task/{taskId}/mark-message-read; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/task/{taskId}/mark-message-read")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> markMessageRead(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody UserId userId) {
		if (LicenseStarter.isLicenseActive) {
			clientService.markMessageRead(taskId, userId);
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Найти сообщение в заявке", description = "Найти сообщение в заявке. Endpoint POST /api/v1/client/{clientId}/task/{taskId}/search-messages; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/task/{taskId}/search-messages")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> searchTaskMessage(@PathVariable Long clientId, @PathVariable Long taskId, @RequestBody MessageText messageText) {
		return ResponseEntity.ok().body(clientService.searchTaskMessage(taskId, messageText));
	}


	@Operation(summary = "Отправить сообщение клиенту", description = "Отправить сообщение клиенту. Endpoint POST /api/v1/client/{clientId}/message; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/message")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> newMessage(@PathVariable Long clientId, @RequestBody Message message) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		Message savedMessage = clientService.sendMessage(clientId, message);
		if (savedMessage == null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).build();
		}
		return ResponseEntity.ok(savedMessage);
	}


	@Operation(summary = "Изменить сообщение клиента", description = "Изменить сообщение клиента. Endpoint PATCH /api/v1/client/{clientId}/message/{messageId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/client/{clientId}/message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
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


	@Operation(summary = "Изменить признак необходимости ответа", description = "Изменить признак необходимости ответа. Endpoint PATCH /api/v1/client/{clientId}/message/{messageId}/answer-required; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/client/{clientId}/message/{messageId}/answer-required")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> answerRequired(
			@PathVariable Long clientId,
			@PathVariable Long messageId,
			@RequestBody AnswerRequiredRequest request
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok(clientService.answerRequired(clientId, messageId, request));
	}


	@Operation(summary = "Связать сообщение с заявкой", description = "Связать сообщение с заявкой. Endpoint POST /api/v1/client/{clientId}/link-message-to-task; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/link-message-to-task")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> linkMessageToTask(@PathVariable Long clientId, @RequestBody MessageTask messageTask) {
		if (LicenseStarter.isLicenseActive) {
			MessageTask linkedMessageTask = clientService.linkToTask(messageTask);
			Long taskId = messageTask != null && messageTask.getTask() != null
					? messageTask.getTask().getId()
					: null;
			if (taskId != null) {
				webSocketService.taskUpdated(taskService.buildTaskUpdatedDto(clientId, taskId));
			}
			return ResponseEntity.ok().body(linkedMessageTask);
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить связанное сообщение", description = "Получить связанное сообщение. Endpoint GET /api/v1/client/{clientId}/linked-message; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client/{clientId}/linked-message")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getLinkedMessage(@PathVariable Long clientId, @RequestParam Long linkedMessageId) {
		return ResponseEntity.ok().body(clientService.getMessagesUntilLinkedMessage(clientId, linkedMessageId));
	}


	@Operation(summary = "Удалить сообщение", description = "Удалить сообщение. Endpoint DELETE /api/v1/client/{clientId}/delete-message/{messageId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/client/{clientId}/delete-message/{messageId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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


	@Operation(summary = "Получить страницу сообщений клиента", description = "Получить страницу сообщений клиента. Endpoint GET /api/v1/client/{clientId}/messages-page; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client/{clientId}/messages-page")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getPageOfMessages(@PathVariable Long clientId, @RequestParam Integer page) {
		return ResponseEntity.ok().body(clientService.getPageOfMessages(clientId, page));
	}


	@Operation(summary = "Найти сообщения клиента", description = "Найти сообщения клиента. Endpoint POST /api/v1/client/{clientId}/search-messages; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/search-messages")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> searchMessages(@PathVariable Long clientId, @RequestBody MessageText messageText) {
		return ResponseEntity.ok().body(clientService.searchMessages(clientId, messageText));
	}
}

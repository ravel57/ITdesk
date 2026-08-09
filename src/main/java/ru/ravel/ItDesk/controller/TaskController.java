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
@Tag(name = "Заявки", description = "Создание, изменение, выборка и история заявок.")
public class TaskController {

	private final ClientService clientService;
	private final TaskHistoryService taskHistoryService;
	private final TaskService taskService;
	private final WebSocketService webSocketService;


	@Operation(summary = "Создать заявку", description = "Создать заявку. Endpoint POST /api/v1/client/{clientId}/task; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> newTask(@PathVariable Long clientId, @RequestBody Task task) {
		if (LicenseStarter.isLicenseActive) {
			Task savedTask = taskService.newTask(clientId, task);
			webSocketService.taskUpdated(taskService.buildTaskUpdatedDto(clientId, savedTask.getId()));
			return ResponseEntity.ok().body(savedTask);
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить заявку", description = "Обновить заявку. Endpoint PATCH /api/v1/client/{clientId}/task; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/client/{clientId}/task")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> updateTask(@PathVariable Long clientId, @RequestBody Task task) {
		if (LicenseStarter.isLicenseActive) {
			Task savedTask = taskService.updateTask(clientId, task);
			webSocketService.taskUpdated(taskService.buildTaskUpdatedDto(clientId, savedTask.getId()));
			return ResponseEntity.ok().body(savedTask);
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить страницу заявок", description = "Получить страницу заявок. Endpoint POST /api/v1/tasks-page; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/tasks-page")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getTasksPage(@RequestBody Map<String, Object> request) {
		return ResponseEntity.ok().body(taskService.getTasksPage(request));
	}


	@Operation(summary = "Получить отметки заявок пользователя", description = "Получить отметки заявок пользователя. Endpoint GET /api/v1/client/{clientId}/task-pings; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client/{clientId}/task-pings")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getTaskPings(@PathVariable Long clientId) {
		return ResponseEntity.ok().body(taskService.getTaskPingsForCurrentUser(clientId));
	}


	@Operation(summary = "Получить файлы заявки", description = "Получить файлы заявки. Endpoint GET /api/v1/client/{clientId}/task/{taskId}/files; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client/{clientId}/task/{taskId}/files")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getTaskFiles(
			@PathVariable Long clientId,
			@PathVariable Long taskId
	) {
		return ResponseEntity.ok().body(clientService.getTaskFiles(clientId, taskId));
	}


	@Operation(summary = "Получить историю заявки", description = "Получить историю заявки. Endpoint GET /api/v1/task/{taskId}/history; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/task/{taskId}/history")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getTaskHistory(@PathVariable Long taskId) {
		taskService.getTaskForCurrentUser(taskId);
		return ResponseEntity.ok(taskHistoryService.getTaskHistory(taskId));
	}
}

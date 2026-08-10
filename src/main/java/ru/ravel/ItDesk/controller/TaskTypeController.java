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
@Tag(name = "Типы заявок", description = "Справочник типов заявок и тип по умолчанию.")
public class TaskTypeController {

	private final TaskService taskService;


	@Operation(summary = "Получить типы заявок", description = "Получить типы заявок. Endpoint GET /api/v1/task-types; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/task-types")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getTaskTypes() {
		return ResponseEntity.ok(taskService.getTaskTypes());
	}


	@Operation(summary = "Получить тип заявки", description = "Получить тип заявки. Endpoint GET /api/v1/task-types/{id}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/task-types/{id}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getTaskType(@PathVariable Long id) {
		try {
			return ResponseEntity.ok(taskService.getTaskType(id));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		}
	}


	@Operation(summary = "Изменить порядок типов заявок", description = "Изменить порядок типов заявок. Endpoint PATCH /api/v1/task-types/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/task-types/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortTaskTypes(@RequestBody List<TaskType> taskTypes) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok(taskService.resortTaskTypes(taskTypes));
	}


	@Operation(summary = "Создать тип заявки", description = "Создать тип заявки. Endpoint POST /api/v1/task-types; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/task-types")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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


	@Operation(summary = "Обновить тип заявки", description = "Обновить тип заявки. Endpoint PATCH /api/v1/task-types/{id}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/task-types/{id}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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


	@Operation(summary = "Установить тип заявки по умолчанию", description = "Установить тип заявки по умолчанию. Endpoint PATCH /api/v1/task-types/set-default; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/task-types/set-default")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> taskTypeSetDefaultSelection(@RequestBody TaskType taskType) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(taskService.taskTypeSetDefaultSelection(taskType));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		}
	}


	@Operation(summary = "Удалить тип заявки", description = "Удалить тип заявки. Endpoint DELETE /api/v1/task-types/{id}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/task-types/{id}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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
}
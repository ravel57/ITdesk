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
@Tag(name = "Инциденты", description = "Инциденты, таймлайн, связанные заявки и подтверждение восстановления.")
public class IncidentController {

	private final IncidentService incidentService;


	@Operation(summary = "Получить страницу инцидентов", description = "Получить страницу инцидентов. Endpoint POST /api/v1/incidents-page; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/incidents-page")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getIncidentsPage(@RequestBody(required = false) IncidentPageRequest request) {
		return incidentResponse(() -> incidentService.getPage(request));
	}


	@Operation(summary = "Получить шаблоны обновлений инцидента", description = "Получить шаблоны обновлений инцидента. Endpoint GET /api/v1/incident-update-templates; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/incident-update-templates")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getIncidentUpdateTemplates() {
		return incidentResponse(incidentService::getUpdateTemplates);
	}


	@Operation(summary = "Получить активные инциденты клиента", description = "Получить активные инциденты клиента. Endpoint GET /api/v1/client/incidents/active; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client/incidents/active")
	@PreAuthorize("hasRole('CLIENT')")
	public ResponseEntity<Object> getClientActiveIncidents() {
		return incidentResponse(incidentService::getPortalActiveIncidents);
	}


	@Operation(summary = "Подтвердить восстановление по инциденту", description = "Подтвердить восстановление по инциденту. Endpoint PATCH /api/v1/client/incident/{incidentId}/recovery-confirmation; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/client/incident/{incidentId}/recovery-confirmation")
	@PreAuthorize("hasRole('CLIENT')")
	public ResponseEntity<Object> confirmClientIncidentRecovery(
			@PathVariable Long incidentId,
			@RequestBody IncidentRecoveryConfirmationRequest request
	) {
		return incidentResponse(() -> incidentService.confirmPortalRecovery(incidentId, request));
	}


	@Operation(summary = "Получить инцидент по заявке", description = "Получить инцидент по заявке. Endpoint GET /api/v1/task/{taskId}/incident; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/task/{taskId}/incident")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getIncidentByTask(@PathVariable Long taskId) {
		return incidentResponse(() -> incidentService.getIncidentByTask(taskId));
	}


	@Operation(summary = "Получить инцидент", description = "Получить инцидент. Endpoint GET /api/v1/incident/{incidentId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/incident/{incidentId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getIncident(@PathVariable Long incidentId) {
		return incidentResponse(() -> incidentService.getIncident(incidentId));
	}


	@Operation(summary = "Создать инцидент", description = "Создать инцидент. Endpoint POST /api/v1/incident; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/incident")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> createIncident(@RequestBody IncidentSaveRequest request) {
		return incidentResponse(() -> incidentService.createIncident(request));
	}


	@Operation(summary = "Создать инцидент из заявок", description = "Создать инцидент из заявок. Endpoint POST /api/v1/incident/from-tasks; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/incident/from-tasks")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> createIncidentFromTasks(@RequestBody IncidentSaveRequest request) {
		return incidentResponse(() -> incidentService.createFromTasks(request));
	}


	@Operation(summary = "Обновить инцидент", description = "Обновить инцидент. Endpoint PATCH /api/v1/incident/{incidentId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/incident/{incidentId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> updateIncident(
			@PathVariable Long incidentId,
			@RequestBody IncidentSaveRequest request
	) {
		return incidentResponse(() -> incidentService.updateIncident(incidentId, request));
	}


	@Operation(summary = "Добавить запись в таймлайн инцидента", description = "Добавить запись в таймлайн инцидента. Endpoint POST /api/v1/incident/{incidentId}/timeline; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/incident/{incidentId}/timeline")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> addIncidentTimelineEntry(
			@PathVariable Long incidentId,
			@RequestBody IncidentTimelineRequest request
	) {
		return incidentResponse(() -> incidentService.addTimelineEntry(incidentId, request));
	}


	@Operation(summary = "Привязать заявки к инциденту", description = "Привязать заявки к инциденту. Endpoint POST /api/v1/incident/{incidentId}/tasks; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/incident/{incidentId}/tasks")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> linkIncidentTasks(
			@PathVariable Long incidentId,
			@RequestBody IncidentTaskIdsRequest request
	) {
		return incidentResponse(() -> incidentService.linkTasks(incidentId, request));
	}


	@Operation(summary = "Отвязать заявку от инцидента", description = "Отвязать заявку от инцидента. Endpoint DELETE /api/v1/incident/{incidentId}/task/{taskId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/incident/{incidentId}/task/{taskId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> unlinkIncidentTask(
			@PathVariable Long incidentId,
			@PathVariable Long taskId
	) {
		return incidentResponse(() -> incidentService.unlinkTask(incidentId, taskId));
	}


	@Operation(summary = "Изменить подтверждение восстановления заявки", description = "Изменить подтверждение восстановления заявки. Endpoint PATCH /api/v1/incident/{incidentId}/task/{taskId}/recovery-confirmation; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/incident/{incidentId}/task/{taskId}/recovery-confirmation")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> setIncidentTaskRecoveryConfirmation(
			@PathVariable Long incidentId,
			@PathVariable Long taskId,
			@RequestBody IncidentRecoveryConfirmationRequest request
	) {
		return incidentResponse(() -> incidentService.setRecoveryConfirmation(incidentId, taskId, request));
	}


	@Operation(summary = "Выполнить массовую операцию с заявками инцидента", description = "Выполнить массовую операцию с заявками инцидента. Endpoint POST /api/v1/incident/{incidentId}/tasks/bulk; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/incident/{incidentId}/tasks/bulk")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> runIncidentBulkTaskOperation(
			@PathVariable Long incidentId,
			@RequestBody IncidentBulkTaskRequest request
	) {
		return incidentResponse(() -> incidentService.bulkTaskOperation(incidentId, request));
	}


	@Operation(summary = "Получить рекомендации по инциденту", description = "Получить рекомендации по инциденту. Endpoint GET /api/v1/incident/{incidentId}/suggestions; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/incident/{incidentId}/suggestions")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getIncidentSuggestions(@PathVariable Long incidentId) {
		return incidentResponse(() -> incidentService.getSuggestions(incidentId));
	}


	private ResponseEntity<Object> incidentResponse(Supplier<Object> operation) {
		try {
			return ResponseEntity.ok(operation.get());
		} catch (AccessDeniedException exception) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
					"message",
					exception.getMessage() == null ? "Нет доступа к инциденту" : exception.getMessage()
			));
		} catch (IllegalArgumentException | IllegalStateException exception) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
					"message",
					exception.getMessage() == null
							? "Операция с инцидентом не выполнена"
							: exception.getMessage()
			));
		}
	}
}

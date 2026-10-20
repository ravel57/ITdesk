package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
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
@Hidden
@Tag(name = "Автоматизация", description = "Триггеры, workflow, тестирование маршрутизации и управление запусками автоматизаций.")
public class AutomationController {

	private final AutomationTriggerService automationTriggerService;
	private final AutomationOperationService automationOperationService;
	private final AutomationGraphHistoryService automationGraphHistoryService;
	private final AutomationRoutingTestService automationRoutingTestService;
	private final AutomationWorkflowEngine automationWorkflowEngine;
	private final AutomationWorkflowRunService automationWorkflowRunService;
	private final AutomationExpressionMetadataService automationExpressionMetadataService;


	@GetMapping("/automation/operations")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public Object operations() { return automationOperationService.catalog(); }

    @GetMapping("/automation/backups")
    @PreAuthorize("@access.can('AUTOMATION_MANAGE')")
    public Object graphBackups(@RequestParam Long triggerId) { return automationGraphHistoryService.backups(triggerId); }

	@GetMapping("/automation/history")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public Object history(@RequestParam Long triggerId) { return automationGraphHistoryService.recent(triggerId); }

	@Operation(summary = "Получить триггеры автоматизации", description = "Получить триггеры автоматизации. Endpoint GET /api/v1/triggers; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/triggers")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> getAllTriggers() {
		return ResponseEntity.ok().body(automationTriggerService.list());

	}


	@Operation(summary = "Создать триггер автоматизации", description = "Создать триггер автоматизации. Endpoint POST /api/v1/trigger; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/trigger")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> newTrigger(@RequestBody AutomationTriggerDto dto) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(automationTriggerService.create(dto));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить триггер автоматизации", description = "Обновить триггер автоматизации. Endpoint PATCH /api/v1/trigger; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/trigger")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
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


	@Operation(summary = "Удалить триггер автоматизации", description = "Удалить триггер автоматизации. Endpoint DELETE /api/v1/trigger/{triggerId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/trigger/{triggerId}")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
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


	@Operation(summary = "Изменить порядок триггеров", description = "Изменить порядок триггеров. Endpoint PATCH /api/v1/triggers/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/triggers/resort")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> resortTriggers(@RequestBody List<AutomationTriggerDto> triggers) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(automationTriggerService.resort(triggers));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Протестировать маршрутизацию автоматизации", description = "Протестировать маршрутизацию автоматизации. Endpoint POST /api/v1/automation/test; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/automation/test")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> testAutomationRouting(@RequestBody AutomationRoutingTestRequest request) {
		return ResponseEntity.ok(automationRoutingTestService.test(request));
	}


	@Operation(summary = "Проверить workflow автоматизации", description = "Проверить workflow автоматизации. Endpoint POST /api/v1/automation/validate; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/automation/validate")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> validateAutomationWorkflow(@RequestBody AutomationWorkflowValidationRequest request) {
		return ResponseEntity.ok(automationWorkflowEngine.validateAsMap(request.getWorkflowDefinition()));
	}


	@Operation(summary = "Получить корни выражений автоматизации", description = "Получить корни выражений автоматизации. Endpoint GET /api/v1/automation/expression/roots; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/automation/expression/roots")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> getAutomationExpressionRoots() {
		return ResponseEntity.ok(automationExpressionMetadataService.roots());
	}


	@Operation(summary = "Получить подсказки выражений автоматизации", description = "Получить подсказки выражений автоматизации. Endpoint POST /api/v1/automation/expression/suggestions; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/automation/expression/suggestions")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> getAutomationExpressionSuggestions(
			@RequestBody AutomationExpressionSuggestionRequest request
	) {
		return ResponseEntity.ok(automationExpressionMetadataService.suggest(request));
	}


	@Operation(summary = "Получить запуски автоматизации", description = "Получить запуски автоматизации. Endpoint GET /api/v1/automation/runs; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/automation/runs")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> getAutomationRuns(@RequestParam Long triggerId) {
		return ResponseEntity.ok(automationWorkflowRunService.recent(triggerId));
	}


	@Operation(summary = "Повторить запуск автоматизации", description = "Повторить запуск автоматизации. Endpoint POST /api/v1/automation/runs/{runId}/retry; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/automation/runs/{runId}/retry")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> retryAutomationRun(@PathVariable Long runId) {
		return ResponseEntity.ok(automationWorkflowRunService.retry(runId));
	}


	@Operation(summary = "Подтвердить запуск автоматизации", description = "Подтвердить запуск автоматизации. Endpoint POST /api/v1/automation/runs/{runId}/approve; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/automation/runs/{runId}/approve")
	@PreAuthorize("@access.can('AUTOMATION_APPROVE')")
	public ResponseEntity<Object> approveAutomationRun(
			@PathVariable Long runId,
			@RequestBody(required = false) AutomationApprovalDecisionRequest request
	) {
		return ResponseEntity.ok(automationWorkflowRunService.approve(runId, request));
	}


	@Operation(summary = "Отклонить запуск автоматизации", description = "Отклонить запуск автоматизации. Endpoint POST /api/v1/automation/runs/{runId}/reject; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/automation/runs/{runId}/reject")
	@PreAuthorize("@access.can('AUTOMATION_APPROVE')")
	public ResponseEntity<Object> rejectAutomationRun(
			@PathVariable Long runId,
			@RequestBody(required = false) AutomationApprovalDecisionRequest request
	) {
		return ResponseEntity.ok(automationWorkflowRunService.reject(runId, request));
	}


	@Operation(summary = "Отменить запуск автоматизации", description = "Отменить запуск автоматизации. Endpoint DELETE /api/v1/automation/runs/{runId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/automation/runs/{runId}")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> cancelAutomationRun(@PathVariable Long runId) {
		return ResponseEntity.ok(automationWorkflowRunService.cancel(runId));
	}


	@Operation(summary = "Получить типы триггеров", description = "Получить типы триггеров. Endpoint GET /api/v1/trigger-types; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/trigger-types")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<Object> getTriggerTypes() {
		return ResponseEntity.ok(
				Arrays.stream(TriggerType.values()).map(Enum::name).toList()
		);
	}


	@Operation(summary = "Получить события автоматизаций с названиями",
			description = "Endpoint GET /api/v1/trigger-types/options: код события, название на русском и раздел.")
	@GetMapping("/trigger-types/options")
	@PreAuthorize("@access.can('AUTOMATION_MANAGE')")
	public ResponseEntity<List<TriggerTypeOption>> getTriggerTypeOptions() {
		return ResponseEntity.ok(Arrays.stream(TriggerType.values()).map(TriggerTypeOption::of).toList());
	}
}
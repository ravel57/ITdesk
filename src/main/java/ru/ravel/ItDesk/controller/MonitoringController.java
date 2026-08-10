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
@Tag(name = "Мониторинг", description = "Интеграции Zabbix и контроль состояния услуг.")
public class MonitoringController {

	private final ZabbixMonitoringService zabbixMonitoringService;


	@Operation(summary = "Получить интеграции Zabbix", description = "Получить интеграции Zabbix. Endpoint GET /api/v1/zabbix-integrations; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/zabbix-integrations")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> getZabbixIntegrations() {
		return catalogResponse(zabbixMonitoringService::getIntegrations);
	}


	@Operation(summary = "Создать интеграцию Zabbix", description = "Создать интеграцию Zabbix. Endpoint POST /api/v1/zabbix-integration; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/zabbix-integration")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> createZabbixIntegration(@RequestBody ZabbixIntegrationRequest request) {
		return catalogResponse(() -> zabbixMonitoringService.createIntegration(request));
	}


	@Operation(summary = "Обновить интеграцию Zabbix", description = "Обновить интеграцию Zabbix. Endpoint PATCH /api/v1/zabbix-integration/{integrationId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/zabbix-integration/{integrationId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateZabbixIntegration(
			@PathVariable Long integrationId,
			@RequestBody ZabbixIntegrationRequest request
	) {
		return catalogResponse(() -> zabbixMonitoringService.updateIntegration(integrationId, request));
	}


	@Operation(summary = "Удалить интеграцию Zabbix", description = "Удалить интеграцию Zabbix. Endpoint DELETE /api/v1/zabbix-integration/{integrationId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/zabbix-integration/{integrationId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteZabbixIntegration(@PathVariable Long integrationId) {
		return catalogResponse(() -> {
			zabbixMonitoringService.deleteIntegration(integrationId);
			return Map.of("success", true);
		});
	}


	@Operation(summary = "Проверить интеграцию Zabbix", description = "Проверить интеграцию Zabbix. Endpoint POST /api/v1/zabbix-integration/{integrationId}/test; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/zabbix-integration/{integrationId}/test")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> testZabbixIntegration(@PathVariable Long integrationId) {
		return catalogResponse(() -> zabbixMonitoringService.testIntegration(integrationId));
	}


	@Operation(summary = "Запустить проверку мониторинга услуги", description = "Запустить проверку мониторинга услуги. Endpoint POST /api/v1/service/{serviceId}/monitoring/check; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/service/{serviceId}/monitoring/check")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> checkServiceMonitoring(@PathVariable Long serviceId) {
		return catalogResponse(() -> zabbixMonitoringService.checkService(serviceId));
	}


	@Operation(summary = "Получить сводку мониторинга услуг", description = "Получить сводку мониторинга услуг. Endpoint GET /api/v1/services/monitoring/summary; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/services/monitoring/summary")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getServiceMonitoringSummary() {
		return catalogResponse(zabbixMonitoringService::getMonitoringSummary);
	}


	private ResponseEntity<Object> catalogResponse(Supplier<Object> operation) {
		try {
			return ResponseEntity.ok(operation.get());
		} catch (AccessDeniedException exception) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", Objects.toString(exception.getMessage(), "Нет доступа")));
		} catch (IllegalArgumentException | IllegalStateException exception) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", Objects.toString(exception.getMessage(), "Операция не выполнена")));
		}
	}
}
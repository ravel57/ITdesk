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
@Tag(name = "Каталог услуг", description = "Услуги, критичности и привязки услуг к организациям.")
public class ServiceCatalogController {

	private final UserService userService;
	private final ServiceCatalogService serviceCatalogService;


	@Operation(summary = "Получить каталог услуг", description = "Получить каталог услуг. Endpoint GET /api/v1/services; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/services")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getServices(@RequestParam(defaultValue = "false") boolean includeInactive) {
		return catalogResponse(() -> serviceCatalogService.getServices(includeInactive));
	}


	@Operation(summary = "Получить услугу", description = "Получить услугу. Endpoint GET /api/v1/services/{serviceId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/services/{serviceId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getService(@PathVariable Long serviceId) {
		return catalogResponse(() -> serviceCatalogService.getService(serviceId));
	}


	@Operation(summary = "Получить услуги организации", description = "Получить услуги организации. Endpoint GET /api/v1/organization/{organizationId}/services; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/organization/{organizationId}/services")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER', 'CLIENT')")
	public ResponseEntity<Object> getOrganizationServices(@PathVariable Long organizationId) {
		userService.assertCurrentUserCanAccessOrganizationId(organizationId);
		return catalogResponse(() -> serviceCatalogService.getAvailableServices(organizationId));
	}


	@Operation(summary = "Создать услугу", description = "Создать услугу. Endpoint POST /api/v1/service; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/service")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> createService(@RequestBody ServiceSaveRequest request) {
		return catalogResponse(() -> serviceCatalogService.create(request));
	}


	@Operation(summary = "Обновить услугу", description = "Обновить услугу. Endpoint PATCH /api/v1/service/{serviceId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/service/{serviceId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateService(@PathVariable Long serviceId, @RequestBody ServiceSaveRequest request) {
		return catalogResponse(() -> serviceCatalogService.update(serviceId, request));
	}


	@Operation(summary = "Удалить услугу", description = "Удалить услугу. Endpoint DELETE /api/v1/service/{serviceId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/service/{serviceId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteService(@PathVariable Long serviceId) {
		return catalogResponse(() -> {
			serviceCatalogService.delete(serviceId);
			return Map.of("success", true);
		});
	}


	@Operation(summary = "Изменить порядок услуг", description = "Изменить порядок услуг. Endpoint PATCH /api/v1/services/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/services/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortServices(@RequestBody List<Long> orderedIds) {
		return catalogResponse(() -> serviceCatalogService.resort(orderedIds));
	}


	@Operation(summary = "Получить критичности услуг", description = "Получить критичности услуг. Endpoint GET /api/v1/service-criticalities; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/service-criticalities")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getServiceCriticalities() {
		return catalogResponse(serviceCatalogService::getCriticalities);
	}


	@Operation(summary = "Создать критичность услуги", description = "Создать критичность услуги. Endpoint POST /api/v1/service-criticality; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/service-criticality")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> createServiceCriticality(@RequestBody ServiceCriticality criticality) {
		criticality.setId(null);
		return catalogResponse(() -> serviceCatalogService.saveCriticality(criticality));
	}


	@Operation(summary = "Обновить критичность услуги", description = "Обновить критичность услуги. Endpoint PATCH /api/v1/service-criticality; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/service-criticality")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateServiceCriticality(@RequestBody ServiceCriticality criticality) {
		return catalogResponse(() -> serviceCatalogService.saveCriticality(criticality));
	}


	@Operation(summary = "Удалить критичность услуги", description = "Удалить критичность услуги. Endpoint DELETE /api/v1/service-criticality/{criticalityId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/service-criticality/{criticalityId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteServiceCriticality(@PathVariable Long criticalityId) {
		return catalogResponse(() -> {
			serviceCatalogService.deleteCriticality(criticalityId);
			return Map.of("success", true);
		});
	}


	@Operation(summary = "Получить привязки услуг к организациям", description = "Получить привязки услуг к организациям. Endpoint GET /api/v1/organization-service-assignments; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/organization-service-assignments")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> getOrganizationServiceAssignments(
			@RequestParam(required = false) Long organizationId,
			@RequestParam(required = false) Long serviceId
	) {
		return catalogResponse(() -> serviceCatalogService.getAssignments(organizationId, serviceId));
	}


	@Operation(summary = "Создать привязку услуги к организации", description = "Создать привязку услуги к организации. Endpoint POST /api/v1/organization-service-assignment; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/organization-service-assignment")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> createOrganizationServiceAssignment(@RequestBody OrganizationServiceAssignmentRequest request) {
		return catalogResponse(() -> serviceCatalogService.createAssignment(request));
	}


	@Operation(summary = "Обновить привязку услуги к организации", description = "Обновить привязку услуги к организации. Endpoint PATCH /api/v1/organization-service-assignment/{assignmentId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/organization-service-assignment/{assignmentId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateOrganizationServiceAssignment(
			@PathVariable Long assignmentId,
			@RequestBody OrganizationServiceAssignmentRequest request
	) {
		return catalogResponse(() -> serviceCatalogService.updateAssignment(assignmentId, request));
	}


	@Operation(summary = "Удалить привязку услуги к организации", description = "Удалить привязку услуги к организации. Endpoint DELETE /api/v1/organization-service-assignment/{assignmentId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/organization-service-assignment/{assignmentId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteOrganizationServiceAssignment(@PathVariable Long assignmentId) {
		return catalogResponse(() -> {
			serviceCatalogService.deleteAssignment(assignmentId);
			return Map.of("success", true);
		});
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
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
@Tag(name = "Организации", description = "Организации, причины визитов и журнал посещений.")
public class OrganizationController {

	private final OrganizationService organizationService;
	private final OrganizationVisitReasonService organizationVisitReasonService;
	private final UserService userService;
	private final TaskService taskService;


	@Operation(summary = "Получить организации", description = "Получить организации. Endpoint GET /api/v1/organizations; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/organizations")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getOrganizations() {
		return ResponseEntity.ok().body(
				userService.filterOrganizationsByCurrentUser(organizationService.getOrganizations())
		);
	}


	@Operation(summary = "Получить статистику заявок по организациям", description = "Получить статистику заявок по организациям. Endpoint GET /api/v1/organizations/task-stats; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/organizations/task-stats")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getOrganizationTaskStats() {
		return ResponseEntity.ok().body(taskService.getOrganizationTaskStats());
	}


	@Operation(summary = "Создать организацию", description = "Создать организацию. Endpoint POST /api/v1/organization; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/organization")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> newOrganization(@RequestBody Organization organization) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(organizationService.newOrganization(organization));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить организацию", description = "Обновить организацию. Endpoint PATCH /api/v1/organization; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/organization")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateOrganization(@RequestBody Organization organization) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(organizationService.updateOrganization(organization));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить организацию", description = "Удалить организацию. Endpoint DELETE /api/v1/organization/{organizationId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/organization/{organizationId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteOrganization(@PathVariable Long organizationId) {
		if (LicenseStarter.isLicenseActive) {
			organizationService.deleteOrganization(organizationId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Изменить порядок организаций", description = "Изменить порядок организаций. Endpoint PATCH /api/v1/organizations/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/organizations/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortOrganizations(@RequestBody List<Organization> organizations) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(organizationService.resortOrganizations(organizations));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить причины визитов", description = "Получить причины визитов. Endpoint GET /api/v1/visit-reasons; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/visit-reasons")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getOrganizationVisitReasons() {
		return ResponseEntity.ok().body(organizationVisitReasonService.getReasons());
	}


	@Operation(summary = "Создать причину визита", description = "Создать причину визита. Endpoint POST /api/v1/visit-reason; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/visit-reason")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> newOrganizationVisitReason(@RequestBody OrganizationVisitReason reason) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok().body(organizationVisitReasonService.newReason(reason));
	}


	@Operation(summary = "Обновить причину визита", description = "Обновить причину визита. Endpoint PATCH /api/v1/visit-reason; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/visit-reason")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateOrganizationVisitReason(@RequestBody OrganizationVisitReason reason) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok().body(organizationVisitReasonService.updateReason(reason));
	}


	@Operation(summary = "Удалить причину визита", description = "Удалить причину визита. Endpoint DELETE /api/v1/visit-reason/{reasonId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/visit-reason/{reasonId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteOrganizationVisitReason(@PathVariable Long reasonId) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		organizationVisitReasonService.deleteReason(reasonId);
		return ResponseEntity.noContent().build();
	}


	@Operation(summary = "Изменить порядок причин визитов", description = "Изменить порядок причин визитов. Endpoint PATCH /api/v1/visit-reasons/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/visit-reasons/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortOrganizationVisitReasons(@RequestBody List<OrganizationVisitReason> reasons) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		return ResponseEntity.ok().body(organizationVisitReasonService.resortReasons(reasons));
	}


	@Operation(summary = "Получить визиты организации", description = "Получить визиты организации. Endpoint GET /api/v1/organization/{organizationId}/visits; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/organization/{organizationId}/visits")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getOrganizationVisits(@PathVariable Long organizationId) {
		userService.assertCurrentUserCanAccessOrganizationId(organizationId);
		return ResponseEntity.ok().body(organizationService.getVisitHistory(organizationId));
	}


	@Operation(summary = "Добавить визит организации", description = "Добавить визит организации. Endpoint POST /api/v1/organization/{organizationId}/visits; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/organization/{organizationId}/visits")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> addOrganizationVisit(@PathVariable Long organizationId, @RequestBody OrganizationVisit visit) {
		if (LicenseStarter.isLicenseActive) {
			userService.assertCurrentUserCanAccessOrganizationId(organizationId);
			return ResponseEntity.ok().body(organizationService.addVisit(organizationId, visit));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}
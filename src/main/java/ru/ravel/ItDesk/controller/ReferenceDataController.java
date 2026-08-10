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
@Tag(name = "Справочники заявок", description = "Статусы, приоритеты и шаблоны заявок.")
public class ReferenceDataController {

	private final UserService userService;
	private final StatusService statusService;
	private final PriorityService priorityService;
	private final TemplateService templateService;


	@Operation(summary = "Создать статус", description = "Создать статус. Endpoint POST /api/v1/status; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/status")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> newStatus(@RequestBody Status status) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(statusService.newStatus(status));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить статус", description = "Обновить статус. Endpoint PATCH /api/v1/status; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/status")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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


	@Operation(summary = "Удалить статус", description = "Удалить статус. Endpoint DELETE /api/v1/status/{statusId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/status/{statusId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
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


	@Operation(summary = "Получить приоритеты", description = "Получить приоритеты. Endpoint GET /api/v1/priorities; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/priorities")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getPriorities() {
		return ResponseEntity.ok().body(priorityService.getPriorities());
	}


	@Operation(summary = "Создать приоритет", description = "Создать приоритет. Endpoint POST /api/v1/priority; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/priority")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> newPriority(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.newPriority(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить приоритет", description = "Обновить приоритет. Endpoint PATCH /api/v1/priority; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/priority")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updatePriority(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.updatePriority(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить приоритет", description = "Удалить приоритет. Endpoint DELETE /api/v1/priority/{priorityId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/priority/{priorityId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deletePriority(@PathVariable Long priorityId) {
		if (LicenseStarter.isLicenseActive) {
			priorityService.deletePriority(priorityId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Установить приоритет по умолчанию", description = "Установить приоритет по умолчанию. Endpoint PATCH /api/v1/priority/set-default; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/priority/set-default")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> prioritySetDefaultSelection(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.prioritySetDefaultSelection(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Установить высокий приоритет", description = "Установить высокий приоритет. Endpoint PATCH /api/v1/priority/set-high-priority; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/priority/set-high-priority")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> prioritySetCritical(@RequestBody Priority priority) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.prioritySetCritical(priority));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Изменить порядок приоритетов", description = "Изменить порядок приоритетов. Endpoint PATCH /api/v1/priorities/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/priorities/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortPriorities(@RequestBody List<Priority> priorities) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(priorityService.resortPriorities(priorities));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить шаблоны", description = "Получить шаблоны. Endpoint GET /api/v1/templates; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/templates")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getTemplates() {
		return ResponseEntity.ok().body(templateService.getTemplates());
	}


	@Operation(summary = "Создать шаблон", description = "Создать шаблон. Endpoint POST /api/v1/template; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/template")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> newTemplate(@RequestBody Template template) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(templateService.newTemplate(template));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить шаблон", description = "Обновить шаблон. Endpoint PATCH /api/v1/template; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/template")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateTemplate(@RequestBody Template template) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(templateService.updateTemplate(template));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить шаблон", description = "Удалить шаблон. Endpoint DELETE /api/v1/template/{templateId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/template/{templateId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteTemplate(@PathVariable Long templateId) {
		if (LicenseStarter.isLicenseActive) {
			templateService.deleteTemplate(templateId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Установить статус по умолчанию", description = "Установить статус по умолчанию. Endpoint PATCH /api/v1/status/set-default; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/status/set-default")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> statusSetDefaultSelection(@RequestBody Status status) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(statusService.statusSetDefaultSelection(status));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить активные аутентифицированные сессии", description = "Получить активные аутентифицированные сессии. Endpoint POST /api/v1/get-authenticated-users; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/get-authenticated-users")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getAllAuthenticatedUsers() {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(userService.getUsersOnline());
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Получить статусы", description = "Получить статусы. Endpoint GET /api/v1/statuses; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/statuses")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getStatuses() {
		return ResponseEntity.ok().body(statusService.getStatuses());
	}


	@Operation(summary = "Изменить порядок статусов", description = "Изменить порядок статусов. Endpoint PATCH /api/v1/status/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/status/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortStatuses(@RequestBody List<Status> statuses) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(statusService.resortStatuses(statuses));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Изменить порядок шаблонов", description = "Изменить порядок шаблонов. Endpoint PATCH /api/v1/templates/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/templates/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortTemplates(@RequestBody List<Template> templates) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(templateService.resortTemplates(templates));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}
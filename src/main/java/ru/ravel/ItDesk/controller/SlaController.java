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
@Tag(name = "SLA", description = "Настройка SLA и управление SLA конкретных заявок.")
public class SlaController {

	private final OrganizationService organizationService;
	private final SlaService slaService;
	private final TaskService taskService;
	private final AppSettingsRepository appSettingsRepository;
	private final ClientRepository clientRepository;
	private final UserService userService;


	@Operation(summary = "Получить матрицу SLA", description = "Получить матрицу SLA. Endpoint GET /api/v1/sla; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/sla")
	@PreAuthorize("@access.can('SLA_VIEW')")
	public ResponseEntity<Map<String, Map<String, Map<String, Object>>>> getSlaByPriority() {
		Map<String, Map<String, Map<String, Object>>> out = new HashMap<>();
		Map<Organization, Map<Priority, SlaValue>> slaByPriority = organizationService.getSlaByPriority();
		User currentUser = userService.getCurrentUser();
		// OBSERVER видит SLA только своих организаций и стандартный SLA.
		Set<Long> observerOrganizationIds = userService.isObserver(currentUser)
				? userService.organizationScope(currentUser).organizationIds()
				: null;
		slaByPriority.forEach((organization, priorityMap) -> {
			if (observerOrganizationIds != null
					&& !(organization instanceof DefaultOrganization)
					&& !observerOrganizationIds.contains(organization.getId())) {
				return;
			}
			Map<String, Map<String, Object>> priorities = new HashMap<>();
			priorityMap.forEach((priority, slaValue) -> {
				BigDecimal value = slaValue == null || slaValue.getValue() == null
						? BigDecimal.ZERO
						: slaValue.getValue();
				String unit = slaValue == null || slaValue.getUnit() == null
						? SlaUnit.HOURS.name()
						: slaValue.getUnit().name();
				Map<String, Object> dto = new HashMap<>();
				dto.put("value", value);
				dto.put("unit", unit);
				priorities.put(priority.getName(), dto);
			});
			out.put(organization.getName(), priorities);
		});
		return ResponseEntity.ok(out);
	}


	@Operation(summary = "Приостановить SLA заявки", description = "Приостановить SLA заявки. Endpoint POST /api/v1/task/{taskId}/sla/pause; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/task/{taskId}/sla/pause")
	@PreAuthorize("@access.can('TASK_EDIT')")
	public ResponseEntity<Object> pauseTaskSla(
			@PathVariable Long taskId,
			@RequestParam(required = false) String reason
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.pauseTaskSla(taskId, reason);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@Operation(summary = "Возобновить SLA заявки", description = "Возобновить SLA заявки. Endpoint POST /api/v1/task/{taskId}/sla/resume; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/task/{taskId}/sla/resume")
	@PreAuthorize("@access.can('TASK_EDIT')")
	public ResponseEntity<Object> resumeTaskSla(@PathVariable Long taskId) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.resumeTaskSla(taskId);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@Operation(summary = "Получить SLA заявки", description = "Получить SLA заявки. Endpoint GET /api/v1/task/{taskId}/sla/info; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/task/{taskId}/sla/info")
	@PreAuthorize("@access.can('TASK_VIEW')")
	public ResponseEntity<SlaInfoDto> getTaskSlaInfo(@PathVariable Long taskId) {
		Task task;
		try {
			task = taskService.getTaskForCurrentUser(taskId);
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		}
		Sla sla = task.getSla();
		if (sla == null) {
			return ResponseEntity.ok(new SlaInfoDto(false, null, 0L, 0L, 0.0, 0L));
		}
		return ResponseEntity.ok(buildInfo(sla));
	}


	private SlaInfoDto buildInfo(Sla sla) {
		boolean paused = slaService.isPaused(sla);
		ZonedDateTime deadline = slaService.deadline(sla);
		long pausedSeconds = slaService.getPausedDuration(sla).getSeconds();
		long remainingSeconds = slaService.remaining(sla).getSeconds();
		long workdaySeconds = getWorkdaySeconds();
		double slaWorkingDays = getSlaWorkingDays(sla, workdaySeconds);
		return new SlaInfoDto(
				paused,
				deadline,
				remainingSeconds,
				pausedSeconds,
				slaWorkingDays,
				workdaySeconds
		);
	}


	private long getWorkdaySeconds() {
		return appSettingsRepository.findAll().stream()
				.findFirst()
				.map(AppSettings::getWorkdayDuration)
				.map(Duration::getSeconds)
				.filter(seconds -> seconds > 0)
				.orElse(Duration.ofHours(24).getSeconds());
	}


	private double getSlaWorkingDays(Sla sla, long workdaySeconds) {
		if (sla == null || sla.getDuration() == null || workdaySeconds <= 0) {
			return 0.0;
		}
		double workingDays = (double) sla.getDuration().getSeconds() / (double) workdaySeconds;
		return Math.round(workingDays * 100.0) / 100.0;
	}


	@Operation(summary = "Сохранить настройки SLA", description = "Сохранить настройки SLA. Endpoint POST /api/v1/sla; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/sla")
	@PreAuthorize("@access.can('SLA_MANAGE')")
	public ResponseEntity<Object> postSla(@RequestBody OrganizationPriorityDuration sla) {
		if (LicenseStarter.isLicenseActive) {
			organizationService.setSla(sla);
			return ResponseEntity.ok().build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Приостановить SLA клиентской заявки", description = "Приостановить SLA клиентской заявки. Endpoint POST /api/v1/client/{clientId}/task/{taskId}/sla/pause; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/task/{taskId}/sla/pause")
	@PreAuthorize("@access.can('TASK_EDIT')")
	public ResponseEntity<Object> pauseClientTaskSla(@PathVariable Long clientId, @PathVariable Long taskId, @RequestParam(required = false) String reason) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.pauseTaskSla(clientId, taskId, reason);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@Operation(summary = "Возобновить SLA клиентской заявки", description = "Возобновить SLA клиентской заявки. Endpoint POST /api/v1/client/{clientId}/task/{taskId}/sla/resume; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/client/{clientId}/task/{taskId}/sla/resume")
	@PreAuthorize("@access.can('TASK_EDIT')")
	public ResponseEntity<Object> resumeClientTaskSla(@PathVariable Long clientId, @PathVariable Long taskId) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			Task task = taskService.resumeTaskSla(clientId, taskId);
			if (task.getSla() == null) {
				return ResponseEntity.badRequest().body("У заявки нет SLA");
			}
			return ResponseEntity.ok(buildInfo(task.getSla()));
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}


	@Operation(summary = "Получить SLA клиентской заявки", description = "Получить SLA клиентской заявки. Endpoint GET /api/v1/client/{clientId}/task/{taskId}/sla/info; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/client/{clientId}/task/{taskId}/sla/info")
	@PreAuthorize("@access.can('TASK_VIEW')")
	public ResponseEntity<Object> getClientTaskSlaInfo(@PathVariable Long clientId, @PathVariable Long taskId) {
		Task task;
		try {
			task = taskService.getTaskForCurrentUser(taskId);
		} catch (NoSuchElementException | IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		}
		Client client = clientRepository.findByTaskId(taskId).orElse(null);
		if (client == null || !Objects.equals(client.getId(), clientId)) {
			return ResponseEntity.notFound().build();
		}
		if (task.getSla() == null) {
			return ResponseEntity.ok(new SlaInfoDto(false, null, 0L, 0L, 0.0, 0L));
		}
		return ResponseEntity.ok(buildInfo(task.getSla()));
	}
}
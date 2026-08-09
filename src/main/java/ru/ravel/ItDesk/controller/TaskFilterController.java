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
@Tag(name = "Фильтры заявок", description = "Пользовательские фильтры списка заявок.")
public class TaskFilterController {

	private final TaskFilterService taskFilterService;


	@Operation(summary = "Получить фильтры заявок", description = "Получить фильтры заявок. Endpoint GET /api/v1/filters; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/filters")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getFilters() {
		return ResponseEntity.ok().body(taskFilterService.getVisibleFilters());
	}


	@Operation(summary = "Сохранить фильтр заявок", description = "Сохранить фильтр заявок. Endpoint POST /api/v1/filter; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/filter")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> saveTaskFilter(@RequestBody TaskFilter taskFilter) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(taskFilterService.saveTaskFilter(taskFilter));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить фильтр заявок", description = "Удалить фильтр заявок. Endpoint DELETE /api/v1/filter/{filterId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/filter/{filterId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> deleteTaskFilter(@PathVariable Long filterId) {
		if (LicenseStarter.isLicenseActive) {
			taskFilterService.deleteTaskFilter(filterId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}
}

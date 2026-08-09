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
@Tag(name = "Линии поддержки", description = "Линии поддержки, назначаемые пользователи и порядок линий.")
public class SupportLineController {

	private final SupportLineService supportLineService;


	@Operation(summary = "Получить линии поддержки", description = "Получить линии поддержки. Endpoint GET /api/v1/support-lines; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/support-lines")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getSupportLines() {
		return ResponseEntity.ok(supportLineService.getSupportLines());
	}


	@Operation(summary = "Получить доступных исполнителей линии", description = "Получить доступных исполнителей линии. Endpoint GET /api/v1/support-lines/{supportLineId}/assignable-users; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/support-lines/{supportLineId}/assignable-users")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getSupportLineAssignableUsers(@PathVariable Long supportLineId) {
		return ResponseEntity.ok(supportLineService.getAssignableUsers(supportLineId));
	}


	@Operation(summary = "Создать линию поддержки", description = "Создать линию поддержки. Endpoint POST /api/v1/support-line; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/support-line")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> createSupportLine(@RequestBody SupportLine supportLine) {
		return ResponseEntity.ok(supportLineService.create(supportLine));
	}


	@Operation(summary = "Обновить линию поддержки", description = "Обновить линию поддержки. Endpoint PATCH /api/v1/support-line; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/support-line")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> updateSupportLine(@RequestBody SupportLine supportLine) {
		return ResponseEntity.ok(supportLineService.update(supportLine));
	}


	@Operation(summary = "Удалить линию поддержки", description = "Удалить линию поддержки. Endpoint DELETE /api/v1/support-line/{supportLineId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/support-line/{supportLineId}")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> deleteSupportLine(@PathVariable Long supportLineId) {
		supportLineService.delete(supportLineId);
		return ResponseEntity.noContent().build();
	}


	@Operation(summary = "Установить линию по умолчанию", description = "Установить линию по умолчанию. Endpoint PATCH /api/v1/support-line/set-default; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/support-line/set-default")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> setDefaultSupportLine(@RequestBody SupportLine supportLine) {
		return ResponseEntity.ok(supportLineService.setDefault(supportLine));
	}


	@Operation(summary = "Изменить порядок линий поддержки", description = "Изменить порядок линий поддержки. Endpoint PATCH /api/v1/support-lines/resort; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/support-lines/resort")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> resortSupportLines(@RequestBody List<SupportLine> supportLines) {
		return ResponseEntity.ok(supportLineService.resort(supportLines));
	}
}

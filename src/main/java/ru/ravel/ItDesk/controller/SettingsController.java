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
@Tag(name = "Настройки", description = "Общие настройки приложения.")
public class SettingsController {

	private final AppSettingsService appSettingsService;


	@Operation(summary = "Получить общие настройки", description = "Получить общие настройки. Endpoint GET /api/v1/settings/general; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/settings/general")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
	public ResponseEntity<Object> getGeneralSettings() {
		return ResponseEntity.ok(appSettingsService.getGeneralSettings());
	}


	@Operation(summary = "Обновить общие настройки", description = "Обновить общие настройки. Endpoint PATCH /api/v1/settings/general; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/settings/general")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateGeneralSettings(@RequestBody AppSettings settings) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(appSettingsService.updateGeneralSettings(settings));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}
}
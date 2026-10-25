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

	private final SecurityAuditService securityAuditService;
	private final AppSettingsService appSettingsService;
	private final AnswerWaitingTimeService answerWaitingTimeService;


	@Operation(summary = "Получить общие настройки", description = "Получить общие настройки. Endpoint GET /api/v1/settings/general; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/settings/general")
	@PreAuthorize("@access.can('SETTINGS_VIEW')")
	public ResponseEntity<Object> getGeneralSettings() {
		return ResponseEntity.ok(appSettingsService.getGeneralSettings());
	}


	@Operation(summary = "Настройки отсчёта «Ожидает ответа»", description = "Календарное или рабочее время в «Ожидает ответа» и рабочий календарь для расчёта таймера в списке чатов. Endpoint GET /api/v1/settings/answer-waiting.")
	@GetMapping("/settings/answer-waiting")
	@PreAuthorize("@access.can('CHAT_VIEW')")
	public ResponseEntity<AnswerWaitingSettingsDto> getAnswerWaitingSettings() {
		return ResponseEntity.ok(answerWaitingTimeService.getSettings());
	}


	@Operation(summary = "Границы зон SLA", description = "Начало оранжевой и красной зон SLA в процентах оставшегося времени: по ним раскрашиваются таймеры SLA. Endpoint GET /api/v1/settings/sla-zones.")
	@GetMapping("/settings/sla-zones")
	@PreAuthorize("@access.canAny('TASK_VIEW', 'CHAT_VIEW', 'SLA_VIEW', 'ORGANIZATION_STATS')")
	public ResponseEntity<SlaZonesDto> getSlaZones() {
		return ResponseEntity.ok(appSettingsService.getSlaZones());
	}


	@Operation(summary = "Разделы менеджера организации", description = "Какие разделы видит менеджер организации (OBSERVER). Endpoint GET /api/v1/settings/observer-access.")
	@GetMapping("/settings/observer-access")
	@PreAuthorize("@access.can('SETTINGS_VIEW')")
	public ResponseEntity<Object> getObserverAccess() {
		return ResponseEntity.ok(observerAccess(appSettingsService.getObserverFeatures()));
	}


	@Operation(summary = "Изменить разделы менеджера организации", description = "Body: {\"features\": [\"TASKS\", …]} — открытые разделы. Действует сразу, без повторного входа. Endpoint PATCH /api/v1/settings/observer-access.")
	@PatchMapping("/settings/observer-access")
	@PreAuthorize("@access.can('SETTINGS_MANAGE')")
	public ResponseEntity<Object> updateObserverAccess(@RequestBody Map<String, List<ObserverFeature>> body) {
		Set<ObserverFeature> enabled = appSettingsService.updateObserverFeatures(body == null ? List.of() : body.get("features"));
		securityAuditService.record(SecurityAuditEventType.OBSERVER_ACCESS_CHANGED, "Менеджер организации",
				"Открыты разделы: " + (enabled.isEmpty() ? "нет" : enabled.stream().map(ObserverFeature::getLabel).collect(java.util.stream.Collectors.joining(", "))));
		return ResponseEntity.ok(observerAccess(enabled));
	}


	@Operation(summary = "Настройки безопасности", description = "mfaRequiredForAdmins — двухфакторная аутентификация обязательна для администраторов. Endpoint GET /api/v1/settings/security.")
	@GetMapping("/settings/security")
	@PreAuthorize("@access.can('SETTINGS_VIEW')")
	public ResponseEntity<Object> getSecuritySettings() {
		return ResponseEntity.ok(Map.of(
				"mfaRequiredForAdmins", Boolean.TRUE.equals(appSettingsService.getGeneralSettings().getMfaRequiredForAdmins())));
	}


	@Operation(summary = "Изменить настройки безопасности", description = "Body: {\"mfaRequiredForAdmins\": true}. Endpoint PATCH /api/v1/settings/security.")
	@PatchMapping("/settings/security")
	@PreAuthorize("@access.can('SETTINGS_MANAGE')")
	public ResponseEntity<Object> updateSecuritySettings(@RequestBody Map<String, Boolean> body) {
		boolean required = appSettingsService.updateMfaRequiredForAdmins(body != null && Boolean.TRUE.equals(body.get("mfaRequiredForAdmins")));
		securityAuditService.record(SecurityAuditEventType.SETTINGS_CHANGED, "Безопасность",
				"2FA для администраторов: " + (required ? "обязательна" : "по желанию"));
		return ResponseEntity.ok(Map.of("mfaRequiredForAdmins", required));
	}


	private static List<Map<String, Object>> observerAccess(Set<ObserverFeature> enabled) {
		return Arrays.stream(ObserverFeature.values()).map(feature -> {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("code", feature.name());
			row.put("label", feature.getLabel());
			row.put("enabled", enabled.contains(feature));
			return row;
		}).toList();
	}


	@Operation(summary = "Обновить общие настройки", description = "Обновить общие настройки. Endpoint PATCH /api/v1/settings/general; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/settings/general")
	@PreAuthorize("@access.can('SETTINGS_MANAGE')")
	public ResponseEntity<Object> updateGeneralSettings(@RequestBody AppSettings settings) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			AppSettings saved = appSettingsService.updateGeneralSettings(settings);
			securityAuditService.record(SecurityAuditEventType.SETTINGS_CHANGED, "Общие настройки", null);
			return ResponseEntity.ok(saved);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(e.getMessage());
		}
	}
}
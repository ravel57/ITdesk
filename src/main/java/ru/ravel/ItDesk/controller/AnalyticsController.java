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
@Tag(name = "Аналитика", description = "Справочники и агрегированные показатели аналитики.")
public class AnalyticsController {

	private final AnalyticsService analyticsService;


	@Operation(summary = "Получить справочники аналитики", description = "Получить справочники аналитики. Endpoint GET /api/v1/analytics/dictionaries; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/analytics/dictionaries")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getAnalyticsDictionaries() {
		return ResponseEntity.ok(analyticsService.getDictionaries());
	}


	@Operation(summary = "Получить сводку аналитики", description = "Получить сводку аналитики. Endpoint GET /api/v1/analytics/summary; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/analytics/summary")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> getAnalyticsSummary(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(defaultValue = "DAY") String groupBy,
			@RequestParam(required = false) String typeIds,
			@RequestParam(required = false) String priorityIds,
			@RequestParam(required = false) String executorIds,
			@RequestParam(required = false) String tagIds,
			@RequestParam(required = false) String supportLineIds,
			HttpSession session
	) {
		try {
			return ResponseEntity.ok().body(analyticsService.getSummary(
					getAnalyticsRequestKey(session),
					from,
					to,
					groupBy,
					typeIds,
					priorityIds,
					executorIds,
					tagIds,
					supportLineIds
			));
		} catch (CancellationException ignored) {
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}


	@Operation(summary = "Отменить расчёт аналитики", description = "Отменить расчёт аналитики. Endpoint POST /api/v1/analytics/summary/cancel; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/analytics/summary/cancel")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR', 'OBSERVER')")
	public ResponseEntity<Object> cancelAnalyticsSummary(HttpSession session) {
		analyticsService.cancelSummary(getAnalyticsRequestKey(session));
		return ResponseEntity.noContent().build();
	}


	private String getAnalyticsRequestKey(HttpSession session) {
		if (session == null) {
			return null;
		}
		return "analytics-summary:" + session.getId();
	}
}

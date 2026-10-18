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
@Tag(name = "Уведомления", description = "Настройки и история уведомлений текущего пользователя.")
public class UserNotificationController {

	private final UserService userService;
	private final UserNotificationService userNotificationService;


	@Operation(summary = "Получить настройки уведомлений", description = "Получить настройки уведомлений. Endpoint GET /api/v1/user/notification-settings; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/user/notification-settings")
	@PreAuthorize("@access.can('NOTIFICATIONS')")
	public ResponseEntity<Object> getCurrentUserNotificationSettings() {
		return ResponseEntity.ok().body(userService.getCurrentUserNotificationSettings());
	}


	@Operation(summary = "Обновить настройки уведомлений", description = "Обновить настройки уведомлений. Endpoint PATCH /api/v1/user/notification-settings; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/user/notification-settings")
	@PreAuthorize("@access.can('NOTIFICATIONS')")
	public ResponseEntity<Object> updateCurrentUserNotificationSettings(@RequestBody UserNotificationSettingsDto dto) {
		return ResponseEntity.ok().body(userService.updateCurrentUserNotificationSettings(dto));
	}


	@Operation(summary = "Получить уведомления пользователя", description = "Получить уведомления пользователя. Endpoint GET /api/v1/user/notifications; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/user/notifications")
	@PreAuthorize("@access.can('NOTIFICATIONS')")
	public ResponseEntity<Object> getCurrentUserNotifications(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(defaultValue = "false") boolean unreadOnly
	) {
		return ResponseEntity.ok(
				userNotificationService.getCurrentUserNotifications(page, size, unreadOnly)
		);
	}


	@Operation(summary = "Отметить уведомление прочитанным", description = "Отметить уведомление прочитанным. Endpoint PATCH /api/v1/user/notifications/{notificationId}/read; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/user/notifications/{notificationId}/read")
	@PreAuthorize("@access.can('NOTIFICATIONS')")
	public ResponseEntity<Object> markCurrentUserNotificationAsRead(@PathVariable Long notificationId) {
		try {
			return ResponseEntity.ok(
					userNotificationService.markCurrentUserNotificationAsRead(notificationId)
			);
		} catch (IllegalArgumentException exception) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
		}
	}


	@Operation(summary = "Отметить все уведомления прочитанными", description = "Отметить все уведомления прочитанными. Endpoint PATCH /api/v1/user/notifications/read-all; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/user/notifications/read-all")
	@PreAuthorize("@access.can('NOTIFICATIONS')")
	public ResponseEntity<Object> markAllCurrentUserNotificationsAsRead() {
		long unreadCount = userNotificationService.markAllCurrentUserNotificationsAsRead();
		return ResponseEntity.ok(Map.of("unreadCount", unreadCount));
	}
}
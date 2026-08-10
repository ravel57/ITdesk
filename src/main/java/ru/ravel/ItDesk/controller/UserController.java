package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.component.LicenseStarter;
import ru.ravel.ItDesk.dto.Password;
import ru.ravel.ItDesk.dto.UserDto;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.service.UserService;
import ru.ravel.ItDesk.service.UserSessionService;

import java.util.Map;
import java.util.NoSuchElementException;


@RestController
@RequestMapping("/api/v1")
@CrossOrigin
@RequiredArgsConstructor
@Hidden
@Tag(name = "Пользователи", description = "Управление пользователями, ролями, текущей сессией и присутствием.")
public class UserController {

	private final UserService userService;
	private final UserSessionService userSessionService;


	@Operation(summary = "Получить список пользователей", description = "Получить список пользователей. Endpoint GET /api/v1/users; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/users")
	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
	public ResponseEntity<Object> getUsers() {
		return ResponseEntity.ok().body(userService.getUsers());
	}


	@Operation(summary = "Получить пользователей для администрирования", description = "Получить пользователей для администрирования. Endpoint GET /api/v1/users/manage; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/users/manage")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> getManageableUsers() {
		return ResponseEntity.ok().body(userService.getManageableUsers());
	}


	@Operation(summary = "Изменить пароль", description = "Изменить пароль. Endpoint POST /api/v1/user/change-password; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/user/change-password")
	public ResponseEntity<Object> changePassword(@RequestBody Password password) {
		return ResponseEntity.ok().body(userService.changePassword(password));
	}


	@Operation(summary = "Сменить пароль клиентской учётной записи", description = "Администратор задаёт новый пароль выбранному клиенту.")
	@PostMapping("/user/{userId}/change-password")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Object> changeClientPasswordByAdmin(
			@PathVariable Long userId,
			@RequestBody Password password
	) {
		if (!LicenseStarter.isLicenseActive) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			return ResponseEntity.ok(userService.changeClientPasswordByAdmin(userId, password.getPassword()));
		} catch (IllegalArgumentException | NoSuchElementException exception) {
			return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
		}
	}


	@Operation(summary = "Получить список ролей", description = "Получить список ролей. Endpoint GET /api/v1/roles; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/roles")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> getRoles() {
		return ResponseEntity.ok().body(userService.getRoles());
	}


	@Operation(summary = "Создать пользователя", description = "Создать пользователя. Endpoint POST /api/v1/user; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/user")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> newUser(@RequestBody UserDto user) {
		if (LicenseStarter.isLicenseActive) {
			try {
				return ResponseEntity.ok().body(userService.newUser(user));
			} catch (Exception e) {
				return ResponseEntity.status(HttpStatus.CONFLICT).build();
			}
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Обновить пользователя", description = "Обновить пользователя. Endpoint PATCH /api/v1/user; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PatchMapping("/user")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> updateUser(@RequestBody UserDto user) {
		if (LicenseStarter.isLicenseActive) {
			return ResponseEntity.ok().body(userService.updateUser(user));
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Удалить пользователя", description = "Удалить пользователя. Endpoint DELETE /api/v1/delete-user/{userId}; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@DeleteMapping("/delete-user/{userId}")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> deleteUser(@PathVariable Long userId) {
		if (LicenseStarter.isLicenseActive) {
			userService.deleteUser(userId);
			return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
		} else {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
	}


	@Operation(summary = "Отметить пользователя онлайн", description = "Отметить пользователя онлайн. Endpoint POST /api/v1/user-online; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/user-online")
	public ResponseEntity<Object> userOnline() {
		return ResponseEntity.ok(userService.userOnline());
	}


	@Operation(summary = "Отметить пользователя офлайн", description = "Отметить пользователя офлайн. Endpoint POST /api/v1/user-offline; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/user-offline")
	public ResponseEntity<Object> userOffline(@RequestBody(required = false) User ignoredPayload) {
		userService.userOffline(userService.getCurrentUser().getId());
		return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
	}


	@Operation(summary = "Получить текущую сессию", description = "Получить текущую сессию. Endpoint GET /api/v1/session/current; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/session/current")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> getCurrentSession(HttpSession session) {
		return ResponseEntity.ok(Map.of(
				"sessionId", userSessionService.getOrCreateClientSessionMarker(session)
		));
	}


	@Operation(summary = "Получить текущего пользователя", description = "Получить текущего пользователя. Endpoint GET /api/v1/user/current; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/user/current")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> getCurrentUser() {
		return ResponseEntity.ok(userService.getCurrentUser());
	}
}
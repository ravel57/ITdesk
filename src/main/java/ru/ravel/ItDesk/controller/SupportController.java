package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpServletRequest;
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
@Tag(name = "Системная поддержка", description = "Служебные операции поддержки, лицензии и восстановление доступа.")
public class SupportController {

	private final UserService userService;
	private final PasswordResetRateLimiter passwordResetRateLimiter;


//	@Operation(summary = "Отправить служебное сообщение", description = "Отправить служебное сообщение. Endpoint POST /api/v1/support/send-message; действующие проверки ролей, валидация и бизнес-логика сохранены.")
//	@PostMapping("/support/send-message")
//	@PreAuthorize("hasAnyRole('ADMIN', 'MANAGER', 'OPERATOR')")
//	public ResponseEntity<Object> sendSupportMessage(@RequestBody Message message) {
//		return ResponseEntity.ok(userService.sendSupportMessage(message));
//	}


//	@Operation(summary = "Повторно сохранить служебное сообщение", description = "Повторно сохранить служебное сообщение. Endpoint POST /api/v1/support/resave-message; действующие проверки ролей, валидация и бизнес-логика сохранены.")
//	@PostMapping("/support/resave-message")
//	public ResponseEntity<Object> resaveMessage(@RequestBody Message message, @RequestParam String license) {
//		userService.resaveMessage(license, message);
//		return ResponseEntity.ok().build();
//	}


	@Operation(summary = "Сбросить пароль пользователя", description = "Сбросить пароль пользователя. Endpoint POST /api/v1/support/reset-password; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@PostMapping("/support/reset-password")
	public ResponseEntity<Object> resetPassword(@RequestBody Username username, HttpServletRequest request) {
		String normalizedUsername = username == null ? "" : Objects.toString(username.getUsername(), "").trim();
		if (!passwordResetRateLimiter.allow(request.getRemoteAddr(), normalizedUsername)) {
			return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header("Retry-After", "900")
					.build();
		}
		if (!normalizedUsername.isBlank() && normalizedUsername.length() <= 254) {
			userService.resetPassword(normalizedUsername);
		}
		// Always return a generic response; callers cannot distinguish unknown accounts.
		return ResponseEntity.ok().build();
	}


	@Operation(summary = "Получить сведения о лицензии", description = "Получить сведения о лицензии. Endpoint GET /api/v1/license-info; действующие проверки ролей, валидация и бизнес-логика сохранены.")
	@GetMapping("/license-info")
	@PreAuthorize("hasAnyRole('ADMIN')")
	public ResponseEntity<Object> getLicenseValidUntil() {
		List<User> users = userService.getManageableUsers();
		long maxUsers = Objects.requireNonNullElse(LicenseStarter.maxUsers, 0L);

		Map<Role, Long> countsByRole = new EnumMap<>(Role.class);
		for (Role role : Role.values()) {
			long count = users.stream()
					.filter(this::isEnabledLicenseUser)
					.filter(user -> hasUserAuthority(user, role.name()))
					.count();
			countsByRole.put(role, count);
		}

		List<Map<String, Object>> roleCounts = Arrays.stream(Role.values())
				.map(role -> {
					Map<String, Object> item = new LinkedHashMap<>();
					item.put("code", role.name());
					item.put("name", role.getName());
					item.put("count", countsByRole.getOrDefault(role, 0L));
					item.put("countsTowardsLicense", role.consumesLicense());
					return item;
				})
				.toList();

		long usedUsersCount = users.stream()
				.filter(this::isEnabledLicenseUser)
				.filter(this::isLicenseConsumingUser)
				.count();
		long availableUsersCount = Math.max(0L, maxUsers - usedUsersCount);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("employeesCount", maxUsers);
		result.put("maxUsers", maxUsers);
		result.put("licenseUntil", LicenseStarter.licenseUntil);
		result.put("usedUsersCount", usedUsersCount);
		result.put("availableUsersCount", availableUsersCount);
		result.put("roleCounts", roleCounts);
		result.put("adminsCount", countsByRole.getOrDefault(Role.ADMIN, 0L));
		result.put("managersCount", countsByRole.getOrDefault(Role.MANAGER, 0L));
		result.put("operatorsCount", countsByRole.getOrDefault(Role.OPERATOR, 0L));
		result.put("observersCount", countsByRole.getOrDefault(Role.OBSERVER, 0L));
		result.put("clientsCount", countsByRole.getOrDefault(Role.CLIENT, 0L));
		return ResponseEntity.ok().body(result);
	}

	private boolean isLicenseConsumingUser(User user) {
		return Arrays.stream(Role.values())
				.filter(Role::consumesLicense)
				.anyMatch(role -> hasUserAuthority(user, role.name()));
	}


	private boolean isEnabledLicenseUser(User user) {
		return user != null && user.isEnabled();
	}


	private boolean hasUserAuthority(User user, String authorityName) {
		if (user == null || user.getAuthorities() == null || authorityName == null) {
			return false;
		}
		return user.getAuthorities().stream()
				.filter(Objects::nonNull)
				.map(Object::toString)
				.anyMatch(authority -> authority.equals(authorityName) || authority.equals("ROLE_" + authorityName));
	}
}

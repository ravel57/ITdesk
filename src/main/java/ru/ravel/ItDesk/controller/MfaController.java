package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.UserRepository;
import ru.ravel.ItDesk.service.MfaService;

import java.util.LinkedHashMap;
import java.util.Map;


/**
 * Двухфакторная аутентификация. Пока второй фактор не подтверждён (атрибут сессии {@link MfaService#SESSION_STATE}),
 * MfaEnforcementFilter пропускает только эти адреса.
 */
@RestController
@RequestMapping("/api/v1/auth/mfa")
@RequiredArgsConstructor
@Tag(name = "Двухфакторная аутентификация", description = "Подключение приложения-аутентификатора, проверка кода, коды восстановления.")
public class MfaController {

	private final MfaService mfaService;
	private final UserRepository userRepository;


	@Operation(summary = "Состояние 2FA", description = "state: VERIFY — ввести код, ENROLL — подключить приложение, null — вход завершён.")
	@GetMapping("/status")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> status(HttpSession session) {
		User user = currentUser();
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("state", session.getAttribute(MfaService.SESSION_STATE));
		result.put("username", user.getUsername());
		result.put("enabled", Boolean.TRUE.equals(user.getMfaEnabled()));
		result.put("required", mfaService.required(user));
		result.put("recoveryCodesLeft", mfaService.remainingRecoveryCodes(user));
		result.put("emergencyDisabled", mfaService.isEmergencyDisabled());
		return ResponseEntity.ok(result);
	}


	@Operation(summary = "Начать подключение приложения", description = "Возвращает секрет и otpauth-ссылку для QR-кода.")
	@PostMapping("/enroll/start")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> startEnrollment(HttpSession session) {
		if (session.getAttribute(MfaService.SESSION_STATE) == MfaService.State.VERIFY) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Сначала введите код из приложения"));
		}
		try {
			return ResponseEntity.ok(mfaService.startEnrollment(currentUser()));
		} catch (IllegalStateException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}


	@Operation(summary = "Подтвердить подключение кодом", description = "Включает 2FA и возвращает коды восстановления (показываются один раз).")
	@PostMapping("/enroll/confirm")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> confirmEnrollment(@RequestBody Map<String, String> body, HttpSession session) {
		if (session.getAttribute(MfaService.SESSION_STATE) == MfaService.State.VERIFY) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Сначала введите код из приложения"));
		}
		try {
			User user = currentUser();
			Map<String, Object> result = new LinkedHashMap<>();
			result.put("recoveryCodes", mfaService.confirmEnrollment(user, body == null ? null : body.get("code")));
			completeLogin(session, user, result);
			return ResponseEntity.ok(result);
		} catch (IllegalArgumentException | IllegalStateException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}


	@Operation(summary = "Ввести код при входе", description = "Код из приложения или код восстановления. После 5 ошибок сессия завершается.")
	@PostMapping("/verify")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> verify(@RequestBody Map<String, String> body, HttpServletRequest request) {
		HttpSession session = request.getSession();
		if (session.getAttribute(MfaService.SESSION_STATE) != MfaService.State.VERIFY) {
			return ResponseEntity.badRequest().body(Map.of("message", "Код сейчас не требуется"));
		}
		User user = currentUser();
		if (mfaService.verify(user, body == null ? null : body.get("code"))) {
			Map<String, Object> result = new LinkedHashMap<>();
			completeLogin(session, user, result);
			return ResponseEntity.ok(result);
		}
		int failures = (session.getAttribute(MfaService.SESSION_FAILURES) instanceof Integer count ? count : 0) + 1;
		if (failures >= MfaService.MAX_FAILURES) {
			session.invalidate();
			SecurityContextHolder.clearContext();
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("message", "Слишком много неверных кодов. Войдите заново", "loggedOut", true));
		}
		session.setAttribute(MfaService.SESSION_FAILURES, failures);
		return ResponseEntity.badRequest().body(Map.of("message", "Неверный код", "attemptsLeft", MfaService.MAX_FAILURES - failures));
	}


	@Operation(summary = "Отключить 2FA", description = "Требует действующий код. Администраторам при обязательной 2FA недоступно.")
	@PostMapping("/disable")
	@PreAuthorize("isAuthenticated()")
	public ResponseEntity<Object> disable(@RequestBody Map<String, String> body, HttpSession session) {
		if (session.getAttribute(MfaService.SESSION_STATE) != null) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
		}
		try {
			mfaService.disable(currentUser(), body == null ? null : body.get("code"));
			return ResponseEntity.noContent().build();
		} catch (IllegalArgumentException | IllegalStateException e) {
			return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
		}
	}


	private void completeLogin(HttpSession session, User user, Map<String, Object> result) {
		session.removeAttribute(MfaService.SESSION_STATE);
		session.removeAttribute(MfaService.SESSION_FAILURES);
		boolean client = user.getAuthorities() != null && user.getAuthorities().contains(Role.CLIENT);
		result.put("redirect", client ? "/portal" : "/chats");
	}


	private User currentUser() {
		String username = SecurityContextHolder.getContext().getAuthentication().getName();
		return userRepository.findByUsername(username).orElseThrow();
	}

}

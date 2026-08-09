package ru.ravel.ItDesk.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.ravel.ItDesk.config.JwtBearerAuthenticationToken;
import ru.ravel.ItDesk.dto.JwtGenerateRequest;
import ru.ravel.ItDesk.service.JwtService;
import ru.ravel.ItDesk.service.JwtSigningKeyService;
import ru.ravel.ItDesk.service.SecurityAuditService;
import ru.ravel.ItDesk.service.UserService;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/jwt")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "JWT", description = "Выпуск, аудит и отзыв JWT для доступа к API. Управление доступно только администраторам из веб-сессии.")
public class JwtController {

	private final JwtService jwtService;
	private final JwtSigningKeyService jwtSigningKeyService;
	private final SecurityAuditService securityAuditService;
	private final UserService userService;

	@PostMapping("/generate")
	@Operation(
			summary = "Создать JWT",
			description = "Выпускает именованный Bearer JWT с выбранными scopes для текущего администратора. Управление JWT доступно только из веб-сессии администратора."
	)
	public ResponseEntity<?> generate(
			@RequestBody(required = false) JwtGenerateRequest request,
			HttpServletRequest httpRequest
	) {
		requireWebSession();
		try {
			var currentUser = userService.getCurrentUser();
			var response = jwtService.generateForAdmin(
					currentUser,
					request == null ? null : request.name(),
					request == null ? null : request.expiresInHours(),
					request == null ? null : request.scopes()
			);
			securityAuditService.jwtIssued(response, httpRequest);
			return ResponseEntity.ok(response);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
		}
	}

	@GetMapping("/tokens")
	@Operation(
			summary = "Список выпущенных JWT",
			description = "Возвращает метаданные JWT без значения самого токена: имя, scopes, сроки, последнее использование и состояние отзыва."
	)
	public ResponseEntity<?> listTokens() {
		requireWebSession();
		return ResponseEntity.ok(jwtService.listTokens());
	}

	@GetMapping("/scopes")
	@Operation(summary = "Доступные JWT scopes", description = "Возвращает поддерживаемые scopes, которые администратор может назначить новому JWT.")
	public ResponseEntity<?> listScopes() {
		requireWebSession();
		return ResponseEntity.ok(jwtService.listAvailableScopes());
	}

	@GetMapping("/keys")
	@Operation(
			summary = "Ключи подписи JWT",
			description = "Возвращает только метаданные ключей. Секретный материал никогда не возвращается через API."
	)
	public ResponseEntity<?> listSigningKeys() {
		requireWebSession();
		jwtSigningKeyService.getOrCreateActiveKey();
		return ResponseEntity.ok(jwtSigningKeyService.listKeys());
	}

	@PostMapping("/keys/rotate")
	@Operation(
			summary = "Ротировать ключ подписи JWT",
			description = "Создаёт новый активный signing key. Старые ключи сохраняются для проверки ранее выпущенных JWT до их истечения/отзыва."
	)
	public ResponseEntity<?> rotateSigningKey(HttpServletRequest request) {
		requireWebSession();
		var currentUser = userService.getCurrentUser();
		var key = jwtSigningKeyService.rotate();
		securityAuditService.jwtSigningKeyRotated(key.keyId(), currentUser.getUsername(), request);
		return ResponseEntity.ok(key);
	}

	@PostMapping("/tokens/{tokenId}/revoke")
	@Operation(
			summary = "Отозвать JWT",
			description = "Немедленно помечает JWT отозванным. Последующие запросы с ним получают 401."
	)
	public ResponseEntity<?> revoke(@PathVariable Long tokenId, HttpServletRequest request) {
		requireWebSession();
		try {
			var currentUser = userService.getCurrentUser();
			var token = jwtService.revokeToken(tokenId, currentUser);
			securityAuditService.jwtRevoked(token, currentUser.getUsername(), request);
			return ResponseEntity.ok(token);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
		}
	}

	@PostMapping("/tokens/revoke-all")
	@Operation(
			summary = "Отозвать все активные JWT",
			description = "Отзывает все ещё действующие JWT всех администраторов. Веб-сессии пользователей не затрагиваются."
	)
	public ResponseEntity<?> revokeAll(HttpServletRequest request) {
		requireWebSession();
		try {
			var currentUser = userService.getCurrentUser();
			int revoked = jwtService.revokeAllActive(currentUser);
			securityAuditService.jwtRevokedAll(revoked, currentUser.getUsername(), request);
			return ResponseEntity.ok(Map.of("revoked", revoked));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Map.of("error", safeMessage(e)));
		}
	}

	private void requireWebSession() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtBearerAuthenticationToken) {
			throw new AccessDeniedException("Управление JWT доступно только из веб-сессии администратора");
		}
	}

	private String safeMessage(Exception e) {
		return e.getMessage() == null || e.getMessage().isBlank()
				? "Не удалось выполнить операцию с JWT"
				: e.getMessage();
	}
}

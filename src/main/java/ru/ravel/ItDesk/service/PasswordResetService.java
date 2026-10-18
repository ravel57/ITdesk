package ru.ravel.ItDesk.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.ravel.ItDesk.dto.PasswordResetConfirm;
import ru.ravel.ItDesk.dto.PasswordResetLinkRequest;
import ru.ravel.ItDesk.feign.SupportFeignClient;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.LicenseRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;


/**
 * Смена пароля по ссылке. Ссылку генерирует и отправляет на email админ-система (license-server),
 * пароль до перехода по ссылке не меняется. На странице /reset-password пользователь задаёт новый пароль,
 * инсталляция гасит токен в админ-системе и получает email, для которого он был выдан.
 */
@Service
@RequiredArgsConstructor
public class PasswordResetService {

	private final Logger logger = LoggerFactory.getLogger(this.getClass());

	private final SupportFeignClient supportFeignClient;
	private final LicenseRepository licenseRepository;
	private final UserRepository userRepository;
	private final PasswordEncoder passwordEncoder;
	private final SessionRegistry sessionRegistry;
	private final SecurityAuditService securityAuditService;

	/** Адрес, на котором пользователи открывают ULDesk; пусто — первый из app.allowed-origin. */
	@Value("${app.public-url:}")
	private String publicUrl;

	@Value("${app.allowed-origin:http://localhost:8080}")
	private String allowedOrigins;


	public enum LinkState {VALID, INVALID}


	/** Ответ всегда одинаковый: по нему нельзя узнать, есть ли такой пользователь. */
	public void requestLink(String username) {
		User user = userRepository.findFirstByUsernameIgnoreCase(username).orElse(null);
		if (user == null || !user.isEnabled()) {
			return;
		}
		securityAuditService.record(ru.ravel.ItDesk.model.SecurityAuditEventType.PASSWORD_RESET_LINK_REQUESTED, user.getUsername(), null);
		try {
			supportFeignClient.requestPasswordResetLink(license(), new PasswordResetLinkRequest(user.getUsername(), baseUrl()));
		} catch (FeignException.TooManyRequests e) {
			logger.warn("Password reset link rate limited by license server: userId={}", user.getId());
		} catch (RuntimeException e) {
			logger.error("Password reset link request failed: userId={}", user.getId(), e);
		}
	}


	public LinkState verify(String token) {
		if (isBlank(token)) {
			return LinkState.INVALID;
		}
		try {
			supportFeignClient.verifyPasswordResetLink(license(), new PasswordResetConfirm(token.trim(), null));
			return LinkState.VALID;
		} catch (FeignException.Gone | FeignException.NotFound e) {
			return LinkState.INVALID;
		}
	}


	/**
	 * Проверяет новый пароль, гасит ссылку и меняет пароль пользователю, для которого она выдана.
	 * Открытые сессии пользователя завершаются.
	 *
	 * @throws IllegalArgumentException пароль не подходит (ссылка при этом не гасится)
	 * @throws IllegalStateException    ссылка недействительна или пользователь недоступен
	 */
	public void complete(String token, String rawPassword) {
		String password = Objects.toString(rawPassword, "");
		UserService.validateRawPassword(password);
		if (isBlank(token)) {
			throw new IllegalStateException("Ссылка недействительна");
		}
		Map<String, Object> consumed;
		try {
			consumed = supportFeignClient.consumePasswordResetLink(license(), new PasswordResetConfirm(token.trim(), null));
		} catch (FeignException.Gone | FeignException.NotFound e) {
			throw new IllegalStateException("Ссылка недействительна или уже использована");
		}
		String email = consumed == null ? null : Objects.toString(consumed.get("email"), null);
		User user = isBlank(email) ? null : userRepository.findFirstByUsernameIgnoreCase(email).orElse(null);
		if (user == null || !user.isEnabled()) {
			throw new IllegalStateException("Учётная запись недоступна");
		}
		user.setPassword(passwordEncoder.encode(password));
		userRepository.save(user);
		expireSessions(user.getUsername());
		securityAuditService.passwordChangedByResetLink(user.getUsername());
	}


	private void expireSessions(String username) {
		sessionRegistry.getAllPrincipals().stream()
				.filter(principal -> principal instanceof UserDetails details && details.getUsername().equalsIgnoreCase(username))
				.flatMap(principal -> sessionRegistry.getAllSessions(principal, false).stream())
				.forEach(session -> session.expireNow());
	}


	private UUID license() {
		return licenseRepository.findAll().stream()
				.findFirst()
				.map(ru.ravel.ItDesk.model.License::getLicense)
				.orElseThrow(() -> new IllegalStateException("Инсталляция не зарегистрирована в админ-системе"));
	}


	String baseUrl() {
		String configured = isBlank(publicUrl)
				? Arrays.stream(Objects.toString(allowedOrigins, "").split(",")).map(String::trim).filter(origin -> !origin.isBlank())
						.findFirst().orElse("")
				: publicUrl.trim();
		return configured.replaceAll("/+$", "");
	}


	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}

package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.JwtSigningKeyInfo;
import ru.ravel.ItDesk.model.JwtSigningKeyRecord;
import ru.ravel.ItDesk.repository.AppSettingsRepository;
import ru.ravel.ItDesk.repository.JwtSigningKeyRepository;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class JwtSigningKeyService {

	private static final int MIN_SECRET_BYTES = 32;
	private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
			.withZone(ZoneOffset.UTC);

	private final JwtSigningKeyRepository jwtSigningKeyRepository;
	private final AppSettingsRepository appSettingsRepository;
	private final AppSettingsService appSettingsService;

	@Value("${app.jwt.secret:}")
	private String configuredSecret;

	@Transactional
	public JwtSigningKeyRecord getOrCreateActiveKey() {
		lockKeyRegistry();
		return jwtSigningKeyRepository.findFirstByActiveTrueOrderByCreatedAtDesc()
				.orElseGet(this::createInitialKey);
	}

	@Transactional(readOnly = true)
	public String secretForKeyId(String keyId) {
		if (keyId == null || keyId.isBlank()) {
			throw new IllegalArgumentException("JWT kid не указан");
		}
		JwtSigningKeyRecord key = jwtSigningKeyRepository.findById(keyId.trim())
				.orElseThrow(() -> new IllegalArgumentException("Неизвестный JWT kid"));
		return validateSecret(key.getSecret());
	}

	public String legacySecret() {
		String configured = configuredSecret == null ? "" : configuredSecret.trim();
		if (!configured.isBlank()) {
			return validateSecret(configured);
		}
		return validateSecret(appSettingsService.getOrCreateJwtSigningSecret());
	}

	@Transactional
	public JwtSigningKeyInfo rotate() {
		lockKeyRegistry();
		Instant now = Instant.now();
		List<JwtSigningKeyRecord> activeKeys = jwtSigningKeyRepository.findActiveForUpdate();
		for (JwtSigningKeyRecord active : activeKeys) {
			active.setActive(false);
			active.setRetiredAt(now);
		}
		if (!activeKeys.isEmpty()) {
			jwtSigningKeyRepository.saveAllAndFlush(activeKeys);
		}

		JwtSigningKeyRecord created = jwtSigningKeyRepository.saveAndFlush(newKey(randomSecret(), now));
		return toInfo(created);
	}

	@Transactional(readOnly = true)
	public List<JwtSigningKeyInfo> listKeys() {
		return jwtSigningKeyRepository.findAllByOrderByCreatedAtDesc().stream()
				.map(this::toInfo)
				.toList();
	}

	private JwtSigningKeyRecord createInitialKey() {
		Instant now = Instant.now();
		return jwtSigningKeyRepository.saveAndFlush(newKey(legacySecret(), now));
	}

	private void lockKeyRegistry() {
		appSettingsRepository.insertDefaultIfNotExists();
		appSettingsRepository.findByIdForUpdate(1L)
				.orElseThrow(() -> new IllegalStateException("Не удалось заблокировать реестр JWT-ключей"));
	}

	private JwtSigningKeyRecord newKey(String secret, Instant createdAt) {
		return JwtSigningKeyRecord.builder()
				.keyId("k-" + KEY_DATE.format(createdAt) + "-" + UUID.randomUUID().toString().substring(0, 8))
				.secret(validateSecret(secret))
				.createdAt(createdAt)
				.active(true)
				.build();
	}

	private String randomSecret() {
		byte[] bytes = new byte[64];
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String validateSecret(String value) {
		String secret = value == null ? "" : value.trim();
		if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException("JWT signing key должен содержать не менее 32 байт");
		}
		return secret;
	}

	private JwtSigningKeyInfo toInfo(JwtSigningKeyRecord key) {
		return new JwtSigningKeyInfo(
				key.getKeyId(),
				key.getCreatedAt(),
				key.getRetiredAt(),
				key.isActive()
		);
	}
}

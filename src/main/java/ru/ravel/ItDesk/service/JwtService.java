package ru.ravel.ItDesk.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.dto.JwtScopeInfo;
import ru.ravel.ItDesk.dto.JwtTokenInfo;
import ru.ravel.ItDesk.dto.JwtTokenResponse;
import ru.ravel.ItDesk.model.JwtScope;
import ru.ravel.ItDesk.model.JwtSigningKeyRecord;
import ru.ravel.ItDesk.model.JwtTokenRecord;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.repository.JwtTokenRepository;
import ru.ravel.ItDesk.repository.UserRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class JwtService {

	private static final String HMAC_ALGORITHM = "HmacSHA256";
	private static final String JWT_ALGORITHM = "HS256";
	private static final int MAX_TOKEN_LENGTH = 16_384;
	private static final int MAX_TOKEN_NAME_LENGTH = 120;
	private static final long CLOCK_SKEW_SECONDS = 30;

	private final ObjectMapper objectMapper;
	private final UserRepository userRepository;
	private final JwtTokenRepository jwtTokenRepository;
	private final JwtSigningKeyService jwtSigningKeyService;
	private final String issuer;
	private final String audience;
	private final int maxValidityHours;
	private final long usagePersistIntervalSeconds;

	public JwtService(
			@Qualifier("legacyObjectMapper") ObjectMapper objectMapper,
			UserRepository userRepository,
			JwtTokenRepository jwtTokenRepository,
			JwtSigningKeyService jwtSigningKeyService,
			@Value("${app.jwt.issuer:uldesk}") String issuer,
			@Value("${app.jwt.audience:uldesk-api}") String audience,
			@Value("${app.jwt.max-validity-hours:2160}") int maxValidityHours,
			@Value("${app.jwt.usage-persist-interval-seconds:30}") long usagePersistIntervalSeconds
	) {
		this.objectMapper = objectMapper;
		this.userRepository = userRepository;
		this.jwtTokenRepository = jwtTokenRepository;
		this.jwtSigningKeyService = jwtSigningKeyService;
		this.issuer = normalizeRequired(issuer, "JWT issuer");
		this.audience = normalizeRequired(audience, "JWT audience");
		this.maxValidityHours = Math.max(1, maxValidityHours);
		this.usagePersistIntervalSeconds = Math.max(0L, usagePersistIntervalSeconds);
	}

	@Transactional
	public JwtTokenResponse generateForAdmin(
			User user,
			String requestedName,
			Integer requestedValidityHours,
			List<String> requestedScopes
	) {
		if (!isActiveAdmin(user)) {
			throw new IllegalArgumentException("JWT можно создавать только для активного администратора");
		}

		String name = normalizeTokenName(requestedName);
		List<String> scopes = JwtScope.normalize(requestedScopes);
		int validityHours = requestedValidityHours == null ? 24 : requestedValidityHours;
		if (validityHours < 1 || validityHours > maxValidityHours) {
			throw new IllegalArgumentException(
					"Срок JWT должен быть от 1 до " + maxValidityHours + " часов"
			);
		}

		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(validityHours, ChronoUnit.HOURS);
		String jti = UUID.randomUUID().toString();
		List<String> authorities = user.getAuthorities().stream()
				.map(authority -> authority.getAuthority())
				.toList();
		JwtSigningKeyRecord signingKey = jwtSigningKeyService.getOrCreateActiveKey();

		Map<String, Object> header = new LinkedHashMap<>();
		header.put("alg", JWT_ALGORITHM);
		header.put("typ", "JWT");
		header.put("kid", signingKey.getKeyId());

		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", issuer);
		claims.put("aud", audience);
		claims.put("sub", user.getUsername());
		claims.put("uid", user.getId());
		claims.put("roles", authorities);
		claims.put("scope", String.join(" ", scopes));
		claims.put("iat", issuedAt.getEpochSecond());
		claims.put("exp", expiresAt.getEpochSecond());
		claims.put("jti", jti);

		String encodedHeader = encodeJson(header);
		String encodedPayload = encodeJson(claims);
		String signingInput = encodedHeader + "." + encodedPayload;
		String signature = Base64.getUrlEncoder().withoutPadding()
				.encodeToString(sign(signingInput, signingKey.getSecret()));
		String token = signingInput + "." + signature;

		JwtTokenRecord tokenRecord = jwtTokenRepository.saveAndFlush(JwtTokenRecord.builder()
				.jti(jti)
				.name(name)
				.userId(user.getId())
				.username(user.getUsername())
				.scopes(serializeScopes(scopes))
				.signingKeyId(signingKey.getKeyId())
				.issuedAt(issuedAt)
				.expiresAt(expiresAt)
				.build());

		return new JwtTokenResponse(
				tokenRecord.getId(),
				jti,
				name,
				token,
				"Bearer",
				issuedAt,
				expiresAt,
				user.getUsername(),
				authorities,
				scopes,
				signingKey.getKeyId()
		);
	}

	@Transactional
	public JwtAuthenticationResult authenticate(String token) {
		VerifiedJwt verified = verifyAndReadClaims(token);
		Map<String, Object> claims = verified.claims();
		String username = asRequiredString(claims.get("sub"), "sub");
		Long claimedUserId = asLong(claims.get("uid"), "uid");
		String jti = asRequiredString(claims.get("jti"), "jti");
		Instant issuedAt = Instant.ofEpochSecond(asLong(claims.get("iat"), "iat"));
		Instant expiresAt = Instant.ofEpochSecond(asLong(claims.get("exp"), "exp"));

		JwtTokenRecord tokenRecord = jwtTokenRepository.findByJti(jti)
				.orElseThrow(() -> new IllegalArgumentException("JWT не зарегистрирован или был отозван"));
		validateTokenRecord(tokenRecord, claimedUserId, username, issuedAt, expiresAt, verified.keyId());

		List<String> recordScopes = parseScopes(tokenRecord.getScopes());
		Object scopeClaim = claims.get("scope");
		if (scopeClaim != null) {
			List<String> claimedScopes = parseScopes(String.valueOf(scopeClaim));
			if (!recordScopes.equals(claimedScopes)) {
				throw new IllegalArgumentException("JWT scope не соответствует записи выпуска");
			}
		}

		User user = userRepository.findByUsername(username)
				.orElseThrow(() -> new IllegalArgumentException("Пользователь JWT не найден"));
		if (!Objects.equals(user.getId(), claimedUserId)) {
			throw new IllegalArgumentException("JWT выпущен для другого пользователя");
		}
		if (!isActiveAdmin(user)) {
			throw new IllegalArgumentException("JWT доступен только активным администраторам");
		}

		return new JwtAuthenticationResult(
				user,
				tokenRecord.getId(),
				tokenRecord.getJti(),
				tokenRecord.getName(),
				recordScopes
		);
	}

	@Transactional
	public void recordUsage(Long tokenId, String ip, String userAgent, String endpoint) {
		if (tokenId == null) {
			return;
		}
		Instant usedAt = Instant.now();
		jwtTokenRepository.updateUsage(
				tokenId,
				usedAt,
				usedAt.minusSeconds(usagePersistIntervalSeconds),
				limit(ip, 64),
				limit(userAgent, 512),
				limit(endpoint, 512)
		);
	}

	/**
	 * Backward-compatible overload for internal callers created before token naming/scopes were introduced.
	 */
	@Transactional
	public JwtTokenResponse generateForAdmin(User user, Integer requestedValidityHours) {
		return generateForAdmin(user, "API token", requestedValidityHours, List.of(JwtScope.API_FULL.getValue()));
	}

	@Transactional(readOnly = true)
	public List<JwtTokenInfo> listTokens() {
		Instant now = Instant.now();
		return jwtTokenRepository.findAllByOrderByIssuedAtDesc().stream()
				.map(record -> toInfo(record, now))
				.toList();
	}

	public List<JwtScopeInfo> listAvailableScopes() {
		return Arrays.stream(JwtScope.values())
				.map(scope -> new JwtScopeInfo(scope.getValue(), scope.getTitle(), scope.getDescription()))
				.toList();
	}

	@Transactional
	public JwtTokenInfo revokeToken(Long tokenId, User revokedBy) {
		if (!isActiveAdmin(revokedBy)) {
			throw new IllegalArgumentException("JWT может отзывать только активный администратор");
		}
		if (tokenId == null) {
			throw new IllegalArgumentException("Не указан JWT для отзыва");
		}

		JwtTokenRecord record = jwtTokenRepository.findById(tokenId)
				.orElseThrow(() -> new IllegalArgumentException("JWT не найден"));
		Instant now = Instant.now();
		if (record.getRevokedAt() == null && record.getExpiresAt().isAfter(now)) {
			record.setRevokedAt(now);
			record.setRevokedByUserId(revokedBy.getId());
			record = jwtTokenRepository.saveAndFlush(record);
		}
		return toInfo(record, now);
	}

	@Transactional
	public int revokeAllActive(User revokedBy) {
		if (!isActiveAdmin(revokedBy)) {
			throw new IllegalArgumentException("JWT может отзывать только активный администратор");
		}
		Instant now = Instant.now();
		return jwtTokenRepository.revokeAllActive(now, revokedBy.getId(), now);
	}

	private void validateTokenRecord(
			JwtTokenRecord record,
			Long claimedUserId,
			String username,
			Instant issuedAt,
			Instant expiresAt,
			String keyId
	) {
		if (record.getRevokedAt() != null) {
			throw new IllegalArgumentException("JWT отозван");
		}
		if (!record.getExpiresAt().isAfter(Instant.now().minusSeconds(CLOCK_SKEW_SECONDS))) {
			throw new IllegalArgumentException("JWT истёк");
		}
		if (!Objects.equals(record.getUserId(), claimedUserId)
				|| !Objects.equals(record.getUsername(), username)
				|| record.getIssuedAt().getEpochSecond() != issuedAt.getEpochSecond()
				|| record.getExpiresAt().getEpochSecond() != expiresAt.getEpochSecond()) {
			throw new IllegalArgumentException("JWT не соответствует записи выпуска");
		}
		if (record.getSigningKeyId() != null && !Objects.equals(record.getSigningKeyId(), keyId)) {
			throw new IllegalArgumentException("JWT подписан другим ключом");
		}
	}

	private JwtTokenInfo toInfo(JwtTokenRecord record, Instant now) {
		boolean active = record.getRevokedAt() == null && record.getExpiresAt().isAfter(now);
		return new JwtTokenInfo(
				record.getId(),
				record.getJti(),
				record.getName(),
				record.getUserId(),
				record.getUsername(),
				parseScopes(record.getScopes()),
				record.getSigningKeyId(),
				record.getIssuedAt(),
				record.getExpiresAt(),
				record.getRevokedAt(),
				record.getRevokedByUserId(),
				record.getLastUsedAt(),
				record.getLastUsedIp(),
				record.getLastUserAgent(),
				record.getLastEndpoint(),
				record.getUseCount(),
				active
		);
	}

	private VerifiedJwt verifyAndReadClaims(String token) {
		if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
			throw new IllegalArgumentException("Некорректный JWT");
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
			throw new IllegalArgumentException("Некорректный JWT");
		}

		Map<String, Object> header = decodeJson(parts[0]);
		if (!JWT_ALGORITHM.equals(header.get("alg"))) {
			throw new IllegalArgumentException("Неподдерживаемый алгоритм JWT");
		}
		Object typ = header.get("typ");
		if (typ != null && !"JWT".equals(typ)) {
			throw new IllegalArgumentException("Некорректный тип JWT");
		}
		String keyId = optionalString(header.get("kid"));
		String secret = keyId == null
				? jwtSigningKeyService.legacySecret()
				: jwtSigningKeyService.secretForKeyId(keyId);

		byte[] expectedSignature = sign(parts[0] + "." + parts[1], secret);
		byte[] actualSignature;
		try {
			actualSignature = Base64.getUrlDecoder().decode(parts[2]);
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Некорректная подпись JWT", e);
		}
		if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
			throw new IllegalArgumentException("Неверная подпись JWT");
		}

		Map<String, Object> claims = decodeJson(parts[1]);
		if (!issuer.equals(asRequiredString(claims.get("iss"), "iss"))) {
			throw new IllegalArgumentException("Некорректный issuer JWT");
		}
		if (!audience.equals(asRequiredString(claims.get("aud"), "aud"))) {
			throw new IllegalArgumentException("Некорректный audience JWT");
		}

		long now = Instant.now().getEpochSecond();
		long issuedAt = asLong(claims.get("iat"), "iat");
		long expiresAt = asLong(claims.get("exp"), "exp");
		if (issuedAt > now + CLOCK_SKEW_SECONDS) {
			throw new IllegalArgumentException("JWT выпущен в будущем");
		}
		if (expiresAt <= now - CLOCK_SKEW_SECONDS || expiresAt <= issuedAt) {
			throw new IllegalArgumentException("JWT истёк");
		}
		asRequiredString(claims.get("jti"), "jti");
		return new VerifiedJwt(claims, keyId);
	}

	private boolean isActiveAdmin(User user) {
		return user != null
				&& user.isEnabled()
				&& user.isAccountNonExpired()
				&& user.isAccountNonLocked()
				&& user.isCredentialsNonExpired()
				&& user.getAuthorities() != null
				&& user.getAuthorities().contains(Role.ADMIN);
	}

	private String encodeJson(Map<String, Object> value) {
		try {
			return Base64.getUrlEncoder().withoutPadding()
					.encodeToString(objectMapper.writeValueAsBytes(value));
		} catch (Exception e) {
			throw new IllegalStateException("Не удалось сформировать JWT", e);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> decodeJson(String encoded) {
		try {
			byte[] json = Base64.getUrlDecoder().decode(encoded);
			Object value = objectMapper.readValue(json, Map.class);
			if (!(value instanceof Map<?, ?>)) {
				throw new IllegalArgumentException("Некорректный JSON JWT");
			}
			return (Map<String, Object>) value;
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalArgumentException("Некорректный JSON JWT", e);
		}
	}

	private byte[] sign(String input, String secret) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
			return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
		} catch (Exception e) {
			throw new IllegalStateException("Не удалось подписать JWT", e);
		}
	}

	private String asRequiredString(Object value, String name) {
		String result = value == null ? "" : String.valueOf(value).trim();
		if (result.isBlank()) {
			throw new IllegalArgumentException("В JWT отсутствует claim " + name);
		}
		return result;
	}

	private String optionalString(Object value) {
		String normalized = value == null ? "" : String.valueOf(value).trim();
		return normalized.isBlank() ? null : normalized;
	}

	private long asLong(Object value, String name) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		try {
			return Long.parseLong(asRequiredString(value, name));
		} catch (NumberFormatException e) {
			throw new IllegalArgumentException("Некорректный claim " + name, e);
		}
	}

	private String normalizeRequired(String value, String label) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isBlank()) {
			throw new IllegalArgumentException(label + " не должен быть пустым");
		}
		return normalized;
	}

	private String normalizeTokenName(String value) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isBlank()) {
			return "API token";
		}
		if (normalized.length() > MAX_TOKEN_NAME_LENGTH) {
			throw new IllegalArgumentException("Название JWT не должно превышать " + MAX_TOKEN_NAME_LENGTH + " символов");
		}
		return normalized;
	}

	private String serializeScopes(List<String> scopes) {
		return String.join(" ", scopes);
	}

	private List<String> parseScopes(String value) {
		if (value == null || value.isBlank()) {
			return List.of(JwtScope.API_FULL.getValue());
		}
		return JwtScope.normalize(List.of(value.trim().split("\\s+")));
	}

	private String limit(String value, int maxLength) {
		if (value == null) {
			return null;
		}
		String normalized = value.replace('\r', ' ').replace('\n', ' ').trim();
		if (normalized.isBlank()) {
			return null;
		}
		return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
	}

	private record VerifiedJwt(Map<String, Object> claims, String keyId) {
	}
}

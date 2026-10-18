package ru.ravel.ItDesk.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.SecurityAuditEventType;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.plugins.PluginSecretCipher;
import ru.ravel.ItDesk.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.*;


/**
 * Двухфакторная аутентификация: после пароля пользователь вводит код из приложения-аутентификатора
 * (или одноразовый код восстановления). Для администраторов её можно сделать обязательной —
 * тогда при входе без настроенной 2FA сначала открывается подключение приложения.
 * <p>
 * Аварийное отключение для восстановления доступа: MFA_DISABLED=true.
 */
@Service
@RequiredArgsConstructor
public class MfaService {

	/** Атрибут сессии: вход по паролю прошёл, но второй фактор ещё не подтверждён. */
	public static final String SESSION_STATE = "ULDESK_MFA_STATE";
	public static final String SESSION_FAILURES = "ULDESK_MFA_FAILURES";
	public static final int MAX_FAILURES = 5;
	public static final String ISSUER = "ULDESK";

	private static final int RECOVERY_CODES = 8;
	private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
	private static final String PLAIN_PREFIX = "plain:";

	private final Logger logger = LoggerFactory.getLogger(getClass());
	private final SecureRandom random = new SecureRandom();

	private final UserRepository userRepository;
	private final TotpService totpService;
	private final AppSettingsService appSettingsService;
	private final PluginSecretCipher cipher;
	private final SecurityAuditService securityAuditService;

	@Value("${app.security.mfa.disabled:false}")
	private boolean emergencyDisabled;


	public enum State {
		/** Ввести код из приложения. */
		VERIFY,
		/** 2FA обязательна, но ещё не настроена: подключить приложение. */
		ENROLL
	}


	/** Что требуется после входа по паролю; пусто — вход завершён. */
	public Optional<State> stateAfterPasswordLogin(User user) {
		if (emergencyDisabled || user == null) {
			return Optional.empty();
		}
		if (Boolean.TRUE.equals(user.getMfaEnabled()) && user.getMfaSecret() != null) {
			return Optional.of(State.VERIFY);
		}
		return required(user) ? Optional.of(State.ENROLL) : Optional.empty();
	}


	public boolean required(User user) {
		return !emergencyDisabled && user != null && user.getAuthorities() != null
				&& user.getAuthorities().contains(Role.ADMIN)
				&& Boolean.TRUE.equals(appSettingsService.getGeneralSettings().getMfaRequiredForAdmins());
	}


	public boolean isEmergencyDisabled() {
		return emergencyDisabled;
	}


	/** Новый секрет для подключения приложения; включится после подтверждения кодом. */
	@Transactional
	public Map<String, String> startEnrollment(User user) {
		if (Boolean.TRUE.equals(user.getMfaEnabled())) {
			throw new IllegalStateException("Двухфакторная аутентификация уже включена");
		}
		String secret = totpService.newSecret();
		user.setMfaPendingSecret(protect(secret));
		userRepository.save(user);
		Map<String, String> result = new LinkedHashMap<>();
		result.put("secret", secret);
		result.put("otpauthUri", totpService.otpauthUri(ISSUER, user.getUsername(), secret));
		return result;
	}


	/**
	 * Проверяет код из приложения по новому секрету и включает 2FA.
	 *
	 * @return коды восстановления — показываются один раз
	 */
	@Transactional
	public List<String> confirmEnrollment(User user, String code) {
		if (user.getMfaPendingSecret() == null) {
			throw new IllegalStateException("Сначала отсканируйте QR-код");
		}
		String secret = reveal(user.getMfaPendingSecret());
		OptionalLong step = totpService.verify(secret, code, null);
		if (step.isEmpty()) {
			securityAuditService.record(SecurityAuditEventType.MFA_FAILED, user.getUsername(), "Подключение приложения: неверный код");
			throw new IllegalArgumentException("Неверный код. Проверьте время на телефоне и введите новый код");
		}
		List<String> recoveryCodes = newRecoveryCodes();
		user.setMfaSecret(user.getMfaPendingSecret());
		user.setMfaPendingSecret(null);
		user.setMfaEnabled(true);
		user.setMfaEnabledAt(ZonedDateTime.now());
		user.setMfaLastStep(step.getAsLong());
		user.setMfaRecoveryCodes(String.join(",", recoveryCodes.stream().map(MfaService::hash).toList()));
		userRepository.save(user);
		securityAuditService.record(SecurityAuditEventType.MFA_ENABLED, user.getUsername(), null);
		return recoveryCodes;
	}


	/** Код из приложения или одноразовый код восстановления. */
	@Transactional
	public boolean verify(User user, String code) {
		if (user == null || user.getMfaSecret() == null || code == null || code.isBlank()) {
			return false;
		}
		String normalized = code.trim().toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
		if (!normalized.matches("\\d+")) {
			return useRecoveryCode(user, normalized);
		}
		OptionalLong step = totpService.verify(reveal(user.getMfaSecret()), normalized, user.getMfaLastStep());
		if (step.isEmpty()) {
			securityAuditService.record(SecurityAuditEventType.MFA_FAILED, user.getUsername(), "Неверный код приложения");
			return false;
		}
		user.setMfaLastStep(step.getAsLong());
		userRepository.save(user);
		securityAuditService.record(SecurityAuditEventType.MFA_SUCCEEDED, user.getUsername(), null);
		return true;
	}


	@Transactional
	public void disable(User user, String code) {
		if (required(user)) {
			throw new IllegalStateException("Для администраторов двухфакторная аутентификация обязательна");
		}
		if (!verify(user, code)) {
			throw new IllegalArgumentException("Неверный код");
		}
		clear(user);
		userRepository.save(user);
		securityAuditService.record(SecurityAuditEventType.MFA_DISABLED, user.getUsername(), null);
	}


	/** Пользователь потерял телефон и коды восстановления: администратор сбрасывает 2FA, при входе её настроят заново. */
	@Transactional
	public void resetByAdmin(Long userId) {
		User user = userRepository.findById(userId).orElseThrow(() -> new NoSuchElementException("Пользователь не найден"));
		clear(user);
		userRepository.save(user);
		securityAuditService.record(SecurityAuditEventType.MFA_RESET_BY_ADMIN, user.getUsername(), null);
	}


	public int remainingRecoveryCodes(User user) {
		String codes = user == null ? null : user.getMfaRecoveryCodes();
		return codes == null || codes.isBlank() ? 0 : codes.split(",").length;
	}


	private boolean useRecoveryCode(User user, String normalized) {
		String hash = hash(normalized);
		List<String> codes = new ArrayList<>(Arrays.asList(Objects.toString(user.getMfaRecoveryCodes(), "").split(",")));
		codes.removeIf(String::isBlank);
		boolean matched = codes.stream().anyMatch(stored -> MessageDigest.isEqual(
				stored.getBytes(StandardCharsets.US_ASCII), hash.getBytes(StandardCharsets.US_ASCII)));
		if (!matched) {
			securityAuditService.record(SecurityAuditEventType.MFA_FAILED, user.getUsername(), "Неверный код восстановления");
			return false;
		}
		codes.remove(hash);
		user.setMfaRecoveryCodes(String.join(",", codes));
		userRepository.save(user);
		securityAuditService.record(SecurityAuditEventType.MFA_SUCCEEDED, user.getUsername(),
				"Код восстановления, осталось: " + codes.size());
		return true;
	}


	private static void clear(User user) {
		user.setMfaEnabled(false);
		user.setMfaSecret(null);
		user.setMfaPendingSecret(null);
		user.setMfaRecoveryCodes(null);
		user.setMfaLastStep(null);
		user.setMfaEnabledAt(null);
	}


	private List<String> newRecoveryCodes() {
		List<String> codes = new ArrayList<>();
		for (int index = 0; index < RECOVERY_CODES; index++) {
			StringBuilder code = new StringBuilder();
			for (int position = 0; position < 10; position++) {
				code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
			}
			codes.add(code.substring(0, 5) + "-" + code.substring(5));
		}
		return codes;
	}


	static String hash(String code) {
		String normalized = code.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}


	/** Секрет шифруется ключом ULDESK_PLUGIN_SECRET_KEY, если он задан; иначе хранится как есть (с предупреждением). */
	private String protect(String secret) {
		if (cipher.isConfigured()) {
			return cipher.encrypt(secret);
		}
		logger.warn("TOTP secret stored without encryption: set ULDESK_PLUGIN_SECRET_KEY to encrypt it");
		return PLAIN_PREFIX + secret;
	}


	private String reveal(String stored) {
		return stored.startsWith(PLAIN_PREFIX) ? stored.substring(PLAIN_PREFIX.length()) : cipher.decrypt(stored);
	}

}

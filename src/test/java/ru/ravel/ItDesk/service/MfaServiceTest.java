package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.ravel.ItDesk.model.AppSettings;
import ru.ravel.ItDesk.model.Role;
import ru.ravel.ItDesk.model.User;
import ru.ravel.ItDesk.plugins.PluginSecretCipher;
import ru.ravel.ItDesk.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


class MfaServiceTest {

	private static final long NOW = 1_800_000_000L;

	private final TotpService totp = new TotpService(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
	private AppSettings settings;
	private MfaService mfa;
	private User admin;
	private User operator;


	@BeforeEach
	void setUp() {
		UserRepository userRepository = mock(UserRepository.class);
		when(userRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		AppSettingsService appSettingsService = mock(AppSettingsService.class);
		settings = new AppSettings();
		when(appSettingsService.getGeneralSettings()).thenReturn(settings);
		mfa = new MfaService(userRepository, totp, appSettingsService, new PluginSecretCipher(""), mock(SecurityAuditService.class));
		admin = User.builder().id(1L).username("admin@uldesk.ru").authorities(List.of(Role.ADMIN)).build();
		operator = User.builder().id(2L).username("op@uldesk.ru").authorities(List.of(Role.OPERATOR)).build();
	}


	@Test
	void adminWithoutMfaMustEnrollOthersLoginDirectly() {
		assertEquals(Optional.of(MfaService.State.ENROLL), mfa.stateAfterPasswordLogin(admin));
		assertEquals(Optional.empty(), mfa.stateAfterPasswordLogin(operator));
		settings.setMfaRequiredForAdmins(false);
		assertEquals(Optional.empty(), mfa.stateAfterPasswordLogin(admin));
	}


	@Test
	void emergencySwitchSkipsSecondFactor() {
		enroll(operator);
		ReflectionTestUtils.setField(mfa, "emergencyDisabled", true);
		assertEquals(Optional.empty(), mfa.stateAfterPasswordLogin(admin));
		assertEquals(Optional.empty(), mfa.stateAfterPasswordLogin(operator));
	}


	@Test
	void enrollmentRequiresValidCodeAndThenAsksForCodeOnEveryLogin() {
		Map<String, String> started = mfa.startEnrollment(operator);
		assertNotNull(operator.getMfaPendingSecret());
		assertFalse(operator.getMfaPendingSecret().contains(started.get("secret")) && !operator.getMfaPendingSecret().startsWith("plain:"));
		assertThrows(IllegalArgumentException.class, () -> mfa.confirmEnrollment(operator, "000000"));
		assertFalse(operator.getMfaEnabled());

		List<String> recoveryCodes = mfa.confirmEnrollment(operator, currentCode(started.get("secret")));
		assertTrue(operator.getMfaEnabled());
		assertNull(operator.getMfaPendingSecret());
		assertEquals(8, recoveryCodes.size());
		assertEquals(8, mfa.remainingRecoveryCodes(operator));
		assertFalse(operator.getMfaRecoveryCodes().contains(recoveryCodes.getFirst()), "коды хранятся только хэшами");
		assertEquals(Optional.of(MfaService.State.VERIFY), mfa.stateAfterPasswordLogin(operator));
	}


	@Test
	void codeWorksOnceAndRecoveryCodesAreSingleUse() {
		Enrolled enrolled = enroll(operator);
		// confirmEnrollment уже принял код текущего шага: тот же код повторно не проходит
		assertFalse(mfa.verify(operator, currentCode(enrolled.secret())));

		String recovery = enrolled.codes().get(3);
		assertTrue(mfa.verify(operator, recovery.toUpperCase()));
		assertFalse(mfa.verify(operator, recovery), "код восстановления одноразовый");
		assertEquals(7, mfa.remainingRecoveryCodes(operator));
		assertFalse(mfa.verify(operator, "wrong-code"));
	}


	@Test
	void requiredAdminCannotDisableButAdminCanResetOthers() {
		Enrolled enrolled = enroll(admin);
		assertThrows(IllegalStateException.class, () -> mfa.disable(admin, enrolled.codes().getFirst()));
		assertTrue(admin.getMfaEnabled());

		Enrolled operatorEnrolled = enroll(operator);
		mfa.disable(operator, operatorEnrolled.codes().getFirst());
		assertFalse(operator.getMfaEnabled());
		assertNull(operator.getMfaSecret());
		assertNull(operator.getMfaRecoveryCodes());
	}


	record Enrolled(String secret, List<String> codes) {
	}


	private Enrolled enroll(User user) {
		user.setMfaEnabled(false);
		user.setMfaSecret(null);
		user.setMfaLastStep(null);
		String secret = mfa.startEnrollment(user).get("secret");
		return new Enrolled(secret, mfa.confirmEnrollment(user, currentCode(secret)));
	}


	private String currentCode(String secret) {
		return totp.code(TotpService.base32Decode(secret), NOW / TotpService.STEP_SECONDS);
	}

}

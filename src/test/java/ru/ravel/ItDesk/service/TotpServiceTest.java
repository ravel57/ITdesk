package ru.ravel.ItDesk.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;


class TotpServiceTest {

	/** Секрет из RFC 6238, приложение B: «12345678901234567890». */
	private static final String RFC_SECRET = TotpService.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));


	@Test
	void matchesRfc6238TestVectors() {
		assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", RFC_SECRET);
		byte[] key = TotpService.base32Decode(RFC_SECRET);
		TotpService totp = new TotpService();
		// В RFC коды 8-значные; 6-значный — последние 6 цифр
		assertEquals("287082", totp.code(key, 59 / 30));
		assertEquals("081804", totp.code(key, 1111111109L / 30));
		assertEquals("050471", totp.code(key, 1111111111L / 30));
		assertEquals("005924", totp.code(key, 1234567890L / 30));
	}


	@Test
	void acceptsNeighbourStepsButNotReplays() {
		TotpService totp = at(1111111109L);
		long step = 1111111109L / 30;
		assertEquals(step, totp.verify(RFC_SECRET, "081804", null).orElseThrow());
		assertEquals(step, totp.verify(RFC_SECRET, " 081 804 ", null).orElseThrow());
		assertTrue(totp.verify(RFC_SECRET, "081804", step).isEmpty(), "тот же код повторно не принимается");
		// предыдущий шаг (часы телефона отстают на 30 секунд) ещё действует
		String previous = totp.code(TotpService.base32Decode(RFC_SECRET), step - 1);
		assertEquals(step - 1, totp.verify(RFC_SECRET, previous, null).orElseThrow());
		String tooOld = totp.code(TotpService.base32Decode(RFC_SECRET), step - 2);
		assertTrue(totp.verify(RFC_SECRET, tooOld, null).isEmpty());
		assertTrue(totp.verify(RFC_SECRET, "12345", null).isEmpty());
		assertTrue(totp.verify(RFC_SECRET, "abcdef", null).isEmpty());
	}


	@Test
	void newSecretsAreRandomBase32AndRoundTrip() {
		TotpService totp = new TotpService();
		String secret = totp.newSecret();
		assertEquals(32, secret.length());
		assertTrue(secret.matches("[A-Z2-7]+"));
		assertNotEquals(secret, totp.newSecret());
		assertEquals(secret, TotpService.base32(TotpService.base32Decode(secret)));
		assertTrue(totp.otpauthUri("ULDESK", "admin@uldesk.ru", secret)
				.startsWith("otpauth://totp/ULDESK%3Aadmin%40uldesk.ru?secret=" + secret + "&issuer=ULDESK"));
	}


	private static TotpService at(long epochSecond) {
		return new TotpService(Clock.fixed(Instant.ofEpochSecond(epochSecond), ZoneOffset.UTC));
	}

}

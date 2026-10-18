package ru.ravel.ItDesk.service;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Locale;
import java.util.OptionalLong;


/**
 * Одноразовые коды приложений-аутентификаторов (Google Authenticator, Яндекс Ключ, 1Password…):
 * TOTP по RFC 6238 — HMAC-SHA1, 6 цифр, шаг 30 секунд.
 */
@Service
public class TotpService {

	public static final int DIGITS = 6;
	public static final long STEP_SECONDS = 30;
	/** Допускаем расхождение часов телефона и сервера на один шаг в каждую сторону. */
	private static final int WINDOW = 1;
	private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

	private final SecureRandom random = new SecureRandom();
	private final Clock clock;


	public TotpService() {
		this(Clock.systemUTC());
	}


	TotpService(Clock clock) {
		this.clock = clock;
	}


	/** Новый секрет: 160 бит в Base32, как ожидают приложения-аутентификаторы. */
	public String newSecret() {
		byte[] bytes = new byte[20];
		random.nextBytes(bytes);
		return base32(bytes);
	}


	public String otpauthUri(String issuer, String account, String secret) {
		String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
		return "otpauth://totp/" + label
				+ "?secret=" + secret
				+ "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
				+ "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
	}


	/**
	 * Номер принятого шага, если код верен и шаг новее lastUsedStep (повтор того же кода отклоняется).
	 */
	public OptionalLong verify(String secret, String code, Long lastUsedStep) {
		String normalized = code == null ? "" : code.replaceAll("\\s", "");
		if (secret == null || !normalized.matches("\\d{" + DIGITS + "}")) {
			return OptionalLong.empty();
		}
		byte[] key = base32Decode(secret);
		long current = currentStep();
		for (long step = current - WINDOW; step <= current + WINDOW; step++) {
			if (lastUsedStep != null && step <= lastUsedStep) {
				continue;
			}
			if (MessageDigest.isEqual(code(key, step).getBytes(StandardCharsets.US_ASCII), normalized.getBytes(StandardCharsets.US_ASCII))) {
				return OptionalLong.of(step);
			}
		}
		return OptionalLong.empty();
	}


	public long currentStep() {
		return clock.instant().getEpochSecond() / STEP_SECONDS;
	}


	String code(byte[] key, long step) {
		try {
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(key, "HmacSHA1"));
			byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
			int offset = hash[hash.length - 1] & 0x0f;
			int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
					| ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
			return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA1 недоступен", e);
		}
	}


	static String base32(byte[] data) {
		StringBuilder out = new StringBuilder();
		int buffer = 0;
		int bits = 0;
		for (byte value : data) {
			buffer = (buffer << 8) | (value & 0xff);
			bits += 8;
			while (bits >= 5) {
				out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
				bits -= 5;
			}
		}
		if (bits > 0) {
			out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
		}
		return out.toString();
	}


	static byte[] base32Decode(String encoded) {
		String normalized = encoded.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
		ByteBuffer out = ByteBuffer.allocate(normalized.length() * 5 / 8);
		int buffer = 0;
		int bits = 0;
		for (char character : normalized.toCharArray()) {
			int index = BASE32.indexOf(character);
			if (index < 0) {
				throw new IllegalArgumentException("Некорректный секрет TOTP");
			}
			buffer = (buffer << 5) | index;
			bits += 5;
			if (bits >= 8) {
				out.put((byte) ((buffer >> (bits - 8)) & 0xff));
				bits -= 8;
			}
		}
		return out.array();
	}

}

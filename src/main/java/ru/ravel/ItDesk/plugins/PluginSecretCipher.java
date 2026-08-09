package ru.ravel.ItDesk.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;


/**
 * Шифрование секретов плагинов при хранении (AES-GCM).
 * <p>
 * Ключ берётся из переменной окружения, а не из базы: дамп базы сам по себе
 * не должен раскрывать токены интеграций. Если ключ не задан, сохранение
 * секретов отклоняется с понятным сообщением — хранить их открытым текстом
 * молча было бы хуже, чем не дать включить функцию.
 */
@Component
public class PluginSecretCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final String PREFIX = "v1:";
	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = 128;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private final SecureRandom secureRandom = new SecureRandom();

	private final SecretKey key;


	public PluginSecretCipher(
			@Value("${uldesk.plugins.secrets.master-key:}")
			String configuredKey
	) {
		this.key = parseKey(configuredKey);
		if (this.key == null) {
			logger.warn("Plugin secrets are not available: ULDESK_PLUGIN_SECRET_KEY is not set."
					+ " Generate one with: openssl rand -base64 32");
		}
	}


	public boolean isConfigured() {
		return key != null;
	}


	public String encrypt(String plain) {
		requireKey();
		if (plain == null) {
			throw new IllegalArgumentException("Значение секрета не заполнено");
		}

		byte[] iv = new byte[IV_BYTES];
		secureRandom.nextBytes(iv);

		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

			byte[] payload = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);

			return PREFIX + Base64.getEncoder().encodeToString(payload);
		} catch (Exception e) {
			throw new IllegalStateException("Не удалось зашифровать секрет плагина", e);
		}
	}


	public String decrypt(String stored) {
		requireKey();
		if (stored == null || !stored.startsWith(PREFIX)) {
			throw new IllegalStateException("Неизвестный формат хранения секрета плагина");
		}

		try {
			byte[] payload = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
			if (payload.length <= IV_BYTES) {
				throw new IllegalStateException("Повреждённое значение секрета плагина");
			}

			byte[] iv = new byte[IV_BYTES];
			System.arraycopy(payload, 0, iv, 0, IV_BYTES);

			byte[] encrypted = new byte[payload.length - IV_BYTES];
			System.arraycopy(payload, IV_BYTES, encrypted, 0, encrypted.length);

			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));

			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		} catch (IllegalStateException e) {
			throw e;
		} catch (Exception e) {
			/* Сообщение намеренно без деталей: оно уходит в лог. */
			throw new IllegalStateException("Не удалось расшифровать секрет плагина."
					+ " Вероятно, изменился ULDESK_PLUGIN_SECRET_KEY", e);
		}
	}


	private void requireKey() {
		if (key == null) {
			throw new IllegalStateException(
					"Секреты плагинов недоступны: не задан ULDESK_PLUGIN_SECRET_KEY"
							+ " (uldesk.plugins.secrets.master-key)"
			);
		}
	}


	private SecretKey parseKey(String configuredKey) {
		if (configuredKey == null || configuredKey.isBlank()) {
			return null;
		}

		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(configuredKey.trim());
		} catch (IllegalArgumentException e) {
			throw new IllegalStateException(
					"uldesk.plugins.secrets.master-key должен быть строкой base64", e
			);
		}

		if (raw.length != 16 && raw.length != 24 && raw.length != 32) {
			throw new IllegalStateException(
					"uldesk.plugins.secrets.master-key должен содержать 16, 24 или 32 байта"
			);
		}

		return new SecretKeySpec(raw, "AES");
	}
}

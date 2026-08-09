package ru.ravel.ItDesk.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import ru.ravel.ItDesk.model.PluginSecretValue;
import ru.ravel.ItDesk.repository.PluginSecretRepository;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;


/**
 * Значения секретов пользовательских плагинов.
 * <p>
 * Значение хранится только в plugin_secret, зашифрованным ключом установки и
 * привязанным к pluginKey. Никаких plugin-specific fallback из application.yaml
 * нет: иначе имя токена снова становится частью поставки, а одинаковое имя
 * секрета может случайно переиспользоваться между разными плагинами.
 */
@Component
public class PluginSecretStore {

	private static final int MAX_SECRET_NAME_LENGTH = 64;
	private static final int MAX_SECRET_VALUE_LENGTH = 4096;

	private final Logger logger = LoggerFactory.getLogger(getClass());

	private final PluginSecretRepository repository;
	private final PluginSecretCipher cipher;


	public PluginSecretStore(
			PluginSecretRepository repository,
			PluginSecretCipher cipher
	) {
		this.repository = repository;
		this.cipher = cipher;
	}


	@Transactional(readOnly = true)
	public String resolve(String pluginKey, String name) {
		String normalized = normalizeName(name);

		Optional<PluginSecretValue> stored =
				repository.findByPluginKeyAndName(pluginKey, normalized);

		if (stored.isPresent()) {
			return cipher.decrypt(stored.get().getValueEncrypted());
		}

		throw new IllegalStateException("Секрет не задан администратором: " + normalized);
	}


	@Transactional(readOnly = true)
	public boolean isConfigured(String pluginKey, String name) {
		String normalized = normalizeName(name);
		return repository.findByPluginKeyAndName(pluginKey, normalized).isPresent();
	}


	@Transactional(readOnly = true)
	public Set<String> configuredNames(String pluginKey) {
		Set<String> names = new LinkedHashSet<>();
		for (PluginSecretValue value : repository.findAllByPluginKey(pluginKey)) {
			names.add(value.getName());
		}
		return Set.copyOf(names);
	}


	@Transactional
	public void save(String pluginKey, String name, String value, String actor) {
		String normalized = normalizeName(name);

		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Значение секрета не заполнено");
		}
		if (value.length() > MAX_SECRET_VALUE_LENGTH) {
			throw new IllegalArgumentException("Значение секрета длиннее " + MAX_SECRET_VALUE_LENGTH + " символов");
		}

		PluginSecretValue entity = repository
				.findByPluginKeyAndName(pluginKey, normalized)
				.orElseGet(() -> PluginSecretValue.builder()
						.pluginKey(pluginKey)
						.name(normalized)
						.build());

		entity.setValueEncrypted(cipher.encrypt(value));
		entity.setUpdatedAt(Instant.now());
		entity.setUpdatedBy(actor);

		repository.save(entity);
		logger.info("Plugin {}: secret {} updated by {}", pluginKey, normalized, actor);
	}


	@Transactional
	public void delete(String pluginKey, String name) {
		String normalized = normalizeName(name);
		repository.findByPluginKeyAndName(pluginKey, normalized).ifPresent(repository::delete);
	}


	@Transactional
	public void removePlugin(String pluginKey) {
		repository.deleteAllByPluginKey(pluginKey);
	}


	private String normalizeName(String name) {
		if (name == null) {
			throw new IllegalArgumentException("Имя секрета не заполнено");
		}

		String normalized = name.trim().toLowerCase(Locale.ROOT);

		if (normalized.isBlank()
				|| normalized.length() > MAX_SECRET_NAME_LENGTH
				|| !normalized.matches("[a-z0-9][a-z0-9._-]*")) {
			throw new IllegalArgumentException("Некорректное имя секрета плагина");
		}

		return normalized;
	}
}

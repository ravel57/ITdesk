package ru.ravel.ItDesk.plugins;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/**
 * Проверенное описание того, как и куда подставляется секрет.
 * <p>
 * Плагин объявляет это в манифесте, потому что формат авторизации знает автор
 * интеграции, а не администратор. Но значение задаёт администратор, и уходит
 * оно только на адреса из {@code sendTo}: даже одобрив плагину доступ к двум
 * разным API, администратор не разрешает отправлять токен одного в другой.
 */
public record PluginSecretBinding(
		String name,
		boolean header,
		String parameter,
		String valuePrefix,
		List<URI> sendTo
) {

	private static final Set<String> ALLOWED_INJECTIONS = Set.of("header", "query");

	/** Заголовки, которыми управляет транспорт: их плагин подменять не должен. */
	private static final Set<String> RESERVED_HEADERS = Set.of(
			"host",
			"content-length",
			"connection",
			"transfer-encoding",
			"upgrade",
			"expect",
			"cookie"
	);


	public static PluginSecretBinding from(PluginManifest.PluginSecretDeclaration declaration) {
		if (declaration == null) {
			throw new IllegalArgumentException("Описание секрета не заполнено");
		}

		String name = declaration.getName() == null
				? ""
				: declaration.getName().trim().toLowerCase(Locale.ROOT);
		if (!name.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
			throw new IllegalArgumentException("Некорректное имя секрета в манифесте: " + declaration.getName());
		}

		String injection = declaration.getInjection() == null || declaration.getInjection().isBlank()
				? "header"
				: declaration.getInjection().trim().toLowerCase(Locale.ROOT);
		if (!ALLOWED_INJECTIONS.contains(injection)) {
			throw new IllegalArgumentException(
					"secrets[" + name + "].injection должен быть header или query"
			);
		}
		boolean header = "header".equals(injection);

		String parameter = declaration.getParameter() == null ? "" : declaration.getParameter().trim();
		if (header) {
			if (!parameter.matches("[A-Za-z0-9-]{1,64}")) {
				throw new IllegalArgumentException(
						"secrets[" + name + "].parameter должен быть именем заголовка"
				);
			}
			if (RESERVED_HEADERS.contains(parameter.toLowerCase(Locale.ROOT))) {
				throw new IllegalArgumentException(
						"secrets[" + name + "].parameter: заголовок " + parameter + " менять нельзя"
				);
			}
		} else if (!parameter.matches("[A-Za-z0-9._~-]{1,64}")) {
			throw new IllegalArgumentException(
					"secrets[" + name + "].parameter должен быть именем query-параметра"
			);
		}

		String valuePrefix = declaration.getValuePrefix() == null ? "" : declaration.getValuePrefix();
		if (valuePrefix.length() > 32 || valuePrefix.contains("\r") || valuePrefix.contains("\n")) {
			throw new IllegalArgumentException("secrets[" + name + "].valuePrefix некорректен");
		}

		if (declaration.getSendTo() == null || declaration.getSendTo().isEmpty()) {
			throw new IllegalArgumentException(
					"secrets[" + name + "].sendTo обязателен: секрет должен уходить только на явно указанные адреса"
			);
		}

		List<URI> targets = new ArrayList<>();
		for (String target : declaration.getSendTo()) {
			targets.add(PluginPermission.parse("http:get:" + target).prefix());
		}

		return new PluginSecretBinding(name, header, parameter, valuePrefix, List.copyOf(targets));
	}
}

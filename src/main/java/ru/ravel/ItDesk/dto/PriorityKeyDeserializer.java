package ru.ravel.ItDesk.dto;

import ru.ravel.ItDesk.model.Priority;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.KeyDeserializer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class PriorityKeyDeserializer extends KeyDeserializer {

	private static final Pattern PRIORITY_PATTERN = Pattern.compile(
			"Priority\\(id=(\\d+), name=(.*?), defaultSelection=(true|false|null), orderNumber=(\\d+), critical=(true|false|null)\\)"
	);

	@Override
	public Object deserializeKey(String key, DeserializationContext ctxt) {
		Matcher matcher = PRIORITY_PATTERN.matcher(key);
		if (!matcher.matches()) {
			throw new IllegalArgumentException("String format is invalid: %s".formatted(key));
		}
		Long id = Long.parseLong(matcher.group(1));
		String name = matcher.group(2);
		Boolean defaultSelection = parseNullableBoolean(matcher.group(3));
		Integer orderNumber = Integer.parseInt(matcher.group(4));
		Boolean critical = parseNullableBoolean(matcher.group(5));

		return new Priority(id, name, defaultSelection, orderNumber, critical);
	}

	private Boolean parseNullableBoolean(String value) {
		if ("null".equals(value)) {
			return null;
		}
		return Boolean.parseBoolean(value);
	}

}
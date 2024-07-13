package ru.ravel.ItDesk.dto;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import ru.ravel.ItDesk.model.Priority;

import java.util.regex.Matcher;
import java.util.regex.Pattern;


public class PriorityKeyDeserializer extends KeyDeserializer {

	@Override
	public Object deserializeKey(String key, DeserializationContext ctxt) {
		Pattern pattern = Pattern.compile("Priority\\(id=(\\d+), name=(.*?), defaultSelection=(true|false|null), orderNumber=(\\d+), critical=(true|false|null)\\)");
		Matcher matcher = pattern.matcher(key);
		if (matcher.matches()) {
			Long id = Long.parseLong(matcher.group(1));
			String name = matcher.group(2);
			Boolean defaultSelection = "null".equals(matcher.group(3)) ? null : Boolean.getBoolean(matcher.group(3));
			Integer orderNumber = Integer.parseInt(matcher.group(4));
			Boolean critical = "null".equals(matcher.group(3)) ? null : Boolean.parseBoolean(matcher.group(5));
			return new Priority(id, name, defaultSelection, orderNumber, critical);
		} else {
			throw new IllegalArgumentException("String format is invalid: %s".formatted(key));
		}
	}

}
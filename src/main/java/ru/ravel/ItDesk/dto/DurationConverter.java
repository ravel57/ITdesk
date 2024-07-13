package ru.ravel.ItDesk.dto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.Duration;

@Converter(autoApply = true)
public class DurationConverter implements AttributeConverter<Duration, Long> {

	@Override
	public Long convertToDatabaseColumn(Duration duration) {
		return duration != null ? duration.getSeconds() : null;
	}

	@Override
	public Duration convertToEntityAttribute(Long millis) {
		return millis != null ? Duration.ofSeconds(millis) : null;
	}
}
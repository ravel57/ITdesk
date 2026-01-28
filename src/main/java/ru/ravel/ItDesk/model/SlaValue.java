package ru.ravel.ItDesk.model;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Duration;

@Embeddable
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SlaValue {

	private BigDecimal value; // например 2.50

	@Enumerated(EnumType.STRING)
	private SlaUnit unit;

	public Duration toDuration() {
		if (value == null) return Duration.ZERO;
		// храним с точностью до 2 знаков, поэтому переводим в минуты аккуратно
		double v = value.doubleValue();
		return switch (unit) {
			case MINUTES -> Duration.ofMinutes(Math.round(v));
			case HOURS -> Duration.ofMinutes(Math.round(v * 60.0));
			case DAYS -> Duration.ofMinutes(Math.round(v * 24.0 * 60.0));
		};
	}
}
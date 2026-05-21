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
		return toDuration(Duration.ofHours(24));
	}


	public Duration toDuration(Duration workdayDuration) {
		if (value == null || unit == null) return Duration.ZERO;
		double v = value.doubleValue();
		return switch (unit) {
			case MINUTES -> Duration.ofMinutes(Math.round(v));
			case HOURS -> Duration.ofMinutes(Math.round(v * 60.0));
			case DAYS -> {
				Duration normalizedWorkdayDuration = normalizeWorkdayDuration(workdayDuration);
				yield Duration.ofMinutes(Math.round(v * normalizedWorkdayDuration.toMinutes()));
			}
		};
	}


	private Duration normalizeWorkdayDuration(Duration workdayDuration) {
		if (workdayDuration == null || workdayDuration.isZero() || workdayDuration.isNegative()) {
			return Duration.ofHours(24);
		}
		return workdayDuration;
	}
}
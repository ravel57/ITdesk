package ru.ravel.ItDesk.dto;


/**
 * Границы зон SLA в процентах времени SLA, которое ещё остаётся до срока.
 * Зелёная зона — больше {@code orangeStartPercent}, оранжевая — от {@code redStartPercent} (не включая) до
 * {@code orangeStartPercent} (включительно), красная — {@code redStartPercent} и меньше; просроченный SLA — красный.
 */
public record SlaZonesDto(int orangeStartPercent, int redStartPercent) {

	public static final int DEFAULT_ORANGE_START_PERCENT = 50;
	public static final int DEFAULT_RED_START_PERCENT = 25;


	public static SlaZonesDto defaults() {
		return new SlaZonesDto(DEFAULT_ORANGE_START_PERCENT, DEFAULT_RED_START_PERCENT);
	}


	/** Допустимо: 1 ≤ красная < оранжевая ≤ 99 (должна остаться и зелёная зона, и красная). */
	public static boolean isValid(Integer orange, Integer red) {
		return orange != null && red != null && red >= 1 && red < orange && orange <= 99;
	}


	/** Значения из настроек; для пустых или некорректных (старые данные) — по умолчанию. */
	public static SlaZonesDto of(Integer orange, Integer red) {
		return isValid(orange, red) ? new SlaZonesDto(orange, red) : defaults();
	}


	/** Доля оставшегося времени (0..1), с которой начинается оранжевая зона. */
	public double orangeStartFraction() {
		return orangeStartPercent / 100.0;
	}
}

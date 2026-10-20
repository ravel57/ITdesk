package ru.ravel.ItDesk.service;

import ru.ravel.ItDesk.model.jev.JevThreshold;

import java.util.EnumMap;
import java.util.Map;


/**
 * Пороги, заданные аргументами действия правила, например jev.createTaskIfNeeded(0.8).
 * Действуют только на время выполнения этого действия и перекрывают значения из настроек Jev;
 * то, что в правиле не задано (null), берётся из настроек.
 */
public record JevThresholdOverride(
		Map<JevThreshold, Double> thresholds,
		Double replyRequired,
		Double replyNotRequired
) {

	private static final ThreadLocal<JevThresholdOverride> CURRENT = new ThreadLocal<>();


	public JevThresholdOverride {
		thresholds = thresholds == null || thresholds.isEmpty()
				? Map.of()
				: new EnumMap<>(thresholds);
	}


	public static JevThresholdOverride of(JevThreshold automation, Double value) {
		Map<JevThreshold, Double> thresholds = new EnumMap<>(JevThreshold.class);
		if (value != null) {
			thresholds.put(automation, value);
		}
		return new JevThresholdOverride(thresholds, null, null);
	}


	public static JevThresholdOverride current() {
		return CURRENT.get();
	}


	public boolean isEmpty() {
		return thresholds.isEmpty() && replyRequired == null && replyNotRequired == null;
	}


	/**
	 * Выполняет действие с этими порогами; по завершении восстанавливает прежние.
	 */
	public void apply(Runnable action) {
		apply(() -> {
			action.run();
			return null;
		});
	}


	/**
	 * То же для действия, которое возвращает результат шага.
	 */
	public <T> T apply(java.util.function.Supplier<T> action) {
		JevThresholdOverride previous = CURRENT.get();
		CURRENT.set(this);
		try {
			return action.get();
		} finally {
			if (previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		}
	}

}

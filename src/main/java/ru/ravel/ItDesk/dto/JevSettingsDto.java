package ru.ravel.ItDesk.dto;

import java.util.List;


/**
 * Настройки Jev для UI. enabled и apiKeyConfigured только для отображения:
 * подключение задаётся в конфиге (JEV_*), сам ключ наружу не отдаётся.
 * Параметры детектора массовых инцидентов здесь больше не хранятся: окно, пороги, пауза и режим задаются
 * нодами правила автоматизации «Детектор массовых инцидентов (Jev)».
 */
public record JevSettingsDto(
		boolean enabled,
		boolean apiKeyConfigured,
		List<Threshold> thresholds,
		double replyRequiredThreshold,
		double replyNotRequiredThreshold,
		List<String> priorityExcluded
) {

	/**
	 * Порог уверенности одной автоматизации.
	 */
	public record Threshold(String key, String label, String hint, double value) {
	}
}

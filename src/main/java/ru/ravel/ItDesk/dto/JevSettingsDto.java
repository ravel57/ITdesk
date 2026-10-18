package ru.ravel.ItDesk.dto;

import java.util.List;


/**
 * Настройки Jev для UI. enabled и apiKeyConfigured только для отображения:
 * подключение задаётся в конфиге (JEV_*), сам ключ наружу не отдаётся.
 */
public record JevSettingsDto(
		boolean enabled,
		boolean apiKeyConfigured,
		List<Threshold> thresholds,
		double replyRequiredThreshold,
		double replyNotRequiredThreshold,
		List<String> priorityExcluded,
		int incidentWindowMinutes,
		int incidentMinClients,
		double incidentOutageThreshold,
		int incidentCooldownMinutes,
		String incidentMode,
		int incidentP1MinClients
) {

	/**
	 * Порог уверенности одной автоматизации.
	 */
	public record Threshold(String key, String label, String hint, double value) {
	}
}

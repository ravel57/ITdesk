package ru.ravel.ItDesk.dto;

import java.util.List;
import java.util.Map;


/**
 * Запрос на сохранение настроек Jev. Параметры подключения здесь не меняются: они задаются в конфиге.
 * thresholds — пороги по автоматизациям (key из JevThreshold → значение), переданные заменяют текущие.
 */
public record JevSettingsRequest(
		Map<String, Double> thresholds,
		Double replyRequiredThreshold,
		Double replyNotRequiredThreshold,
		List<String> priorityExcluded
) {
}

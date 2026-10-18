package ru.ravel.ItDesk.dto;

import ru.ravel.ItDesk.model.automatosation.TriggerType;


/**
 * Событие автоматизации для UI: код, название и раздел.
 */
public record TriggerTypeOption(String value, String label, String group) {

	public static TriggerTypeOption of(TriggerType type) {
		return new TriggerTypeOption(type.name(), type.label(), type.group());
	}
}

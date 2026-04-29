package ru.ravel.ItDesk.model.automatosation;

import lombok.Getter;

@Getter
public enum TriggerFunctionsType {
	STARTS_WITH("startsWith"),
	ENDS_WITH("endsWith"),
	ANY_OF("anyOf"),
	NONE_OF("noneOf"),
	ALL_OF("allOf"),
	IS_NULL("isNull"),
	NOT_NULL("notNull"),
	IS_EMPTY("isEmpty"),
	NOT_EMPTY("notEmpty"),
	IS_TRUE("isTrue"),
	IS_FALSE("isFalse"),
	NOW("now"),
	HOUR("hour"),
	DAY_OF_WEEK("day_of_week"),
	IS_WEEKEND("is_weekend"),
	IS_WORKING_HOURS("is_working_hours"),
	IS_AFTER_HOURS("is_after_hours"),
	;

	private final String operator;

	TriggerFunctionsType(String operator) {
		this.operator = operator;
	}
}
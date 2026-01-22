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
	;

	private final String operator;

	TriggerFunctionsType(String operator) {
		this.operator = operator;
	}
}
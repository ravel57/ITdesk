package ru.ravel.ItDesk.model;

import lombok.Getter;

@Getter
public enum TriggerFunctionsType {
	STARTS_WITH("starts_with"),
	ENDS_WITH("ends_with"),
	ANY_OF("any_of"),
	NONE_OF("none_of"),
	ALL_OF("all_of"),
	IS_NULL("is_null"),
	NOT_NULL("not_null"),
	IS_EMPTY("is_empty"),
	NOT_EMPTY("not_empty"),
	IS_TRUE("is_true"),
	IS_FALSE("is_false"),
	;

	private final String operator;

	TriggerFunctionsType(String operator) {
		this.operator = operator;
	}
}
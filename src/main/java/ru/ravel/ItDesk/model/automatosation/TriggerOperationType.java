package ru.ravel.ItDesk.model.automatosation;

import lombok.Getter;

@Getter
public enum TriggerOperationType {
	EQ("="),
	NOT("!"),
	GT(">"),
	GTE(">="),
	LT("<"),
	LTE("<="),
	IN("in"),
	AND("and"),
	OR("or"),
	;

	private final String operator;

	TriggerOperationType(String operator) {
		this.operator = operator;
	}
}
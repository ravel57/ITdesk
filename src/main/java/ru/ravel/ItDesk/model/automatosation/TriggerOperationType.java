package ru.ravel.ItDesk.model;

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
	AND("&"),
	OR("|"),
	;

	private final String operator;

	TriggerOperationType(String operator) {
		this.operator = operator;
	}
}
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
	NE("!="),
	NOT_IN("not in"),
	LIKE("like"),
	NOT_LIKE("not like"),
	CONTAINS("contains"),
	NOT_CONTAINS("not contains"),
	BETWEEN("between"),
	NOT_BETWEEN("not between")
	;

	private final String operator;

	TriggerOperationType(String operator) {
		this.operator = operator;
	}
}
package ru.ravel.ItDesk.model.automatosation;

public enum EventStatus {
	NEW,
	PROCESSING,
	DONE,
	FAILED,
	CANCELLED,

	RETRYING,
	DEAD_LETTER,
	SKIPPED,
	EXPIRED,
	;
}

package ru.ravel.ItDesk.model.automatosation;

public enum AutomationRunStatus {
	QUEUED,
	RUNNING,
	SUCCESS,
	PARTIAL_SUCCESS,
	SKIPPED,        // условия не подошли / cooldown
	FAILED,
	RETRYING,
	CANCELED,
	TIMEOUT
}
package ru.ravel.ItDesk.model.automatosation;

public enum AutomationWorkflowRunStatus {
	NEW,
	PROCESSING,
	WAITING_EVENT,
	WAITING_APPROVAL,
	DONE,
	FAILED,
	CANCELLED
}

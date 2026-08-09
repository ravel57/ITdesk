package ru.ravel.ItDesk.model.incident;

public enum IncidentStatus {
	DRAFT,
	VERIFYING,
	DECLARED,
	DIAGNOSIS,
	MITIGATION,
	MONITORING,
	RESTORED,
	CLOSED,
	CANCELLED;

	public boolean isActive() {
		return this != CLOSED && this != CANCELLED;
	}

	public boolean requiresCommunicationControl() {
		return this == DECLARED || this == DIAGNOSIS || this == MITIGATION || this == MONITORING;
	}
}

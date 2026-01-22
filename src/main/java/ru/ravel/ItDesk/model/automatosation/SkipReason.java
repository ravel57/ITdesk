package ru.ravel.ItDesk.model.automatosation;

public enum SkipReason {
	CONDITIONS_NOT_MET,
	RULE_DISABLED,
	COOLDOWN_ACTIVE,
	DUPLICATE_EVENT,
	LOOP_GUARD_TRIGGERED,
	PERMISSION_DENIED
}
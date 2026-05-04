package ru.ravel.ItDesk.model.automatosation;

public enum RetryPolicyType {
	NONE,
	FIXED_DELAY,
	EXPONENTIAL_BACKOFF,

	LINEAR_BACKOFF,
	EXPONENTIAL_BACKOFF_WITH_JITTER,
	MANUAL_ONLY,
	;
}
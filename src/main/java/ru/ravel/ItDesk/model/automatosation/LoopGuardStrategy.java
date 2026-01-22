package ru.ravel.ItDesk.model.automatosation;

public enum LoopGuardStrategy {
	NONE,
	EVENT_FINGERPRINT,         // не повторять на одинаковое событие
	MAX_DEPTH,                 // ограничение вложенности RUN_RULE/RUN_MACRO
	MAX_ACTIONS_PER_EVENT      // ограничение действий на событие
}
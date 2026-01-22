package ru.ravel.ItDesk.model.automatosation;

public enum RateLimitStrategy {
	NONE,
	PER_RULE,          // ограничение на правило
	PER_ENTITY,        // ограничение на одну заявку/клиента
	PER_ORGANIZATION   // глобально на организацию
}
package ru.ravel.ItDesk.model;

public enum TriggerType {
	MANUAL_MACRO_APPLIED,            // пользователь применил макрос

	// ---- заявки (tickets/support) ----
	TICKET_CREATED,
	TICKET_UPDATED,                  // любое изменение (грубый триггер)
	TICKET_STATUS_CHANGED,
	TICKET_PRIORITY_CHANGED,
	TICKET_ASSIGNEE_CHANGED,
	TICKET_GROUP_CHANGED,
	TICKET_TAG_ADDED,
	TICKET_TAG_REMOVED,
	TICKET_DUE_DATE_CHANGED,
	TICKET_CLOSED,
	TICKET_REOPENED,

	// ---- сообщения (чат/комментарии) ----
	MESSAGE_INCOMING,                // входящее от клиента
	MESSAGE_OUTGOING,                // исходящее от агента
	MESSAGE_ADDED_ANY,               // любое сообщение
	MESSAGE_MENTIONED_USER,          // @упоминание пользователя
	MESSAGE_CONTAINS_KEYWORD,        // ключевое слово (можно как условие)
	ATTACHMENT_ADDED,

	// ---- SLA / таймеры ----
	SLA_WARNING,                     // скоро нарушится
	SLA_BREACHED,                    // нарушен
	INACTIVITY_TIMEOUT,              // нет ответа X минут/часов
	SCHEDULED_CRON,                  // по расписанию (cron)

	// ---- задачи (если есть tasks) ----
	TASK_CREATED,
	TASK_UPDATED,
	TASK_COMPLETED,
	TASK_OVERDUE,

	// ---- клиенты / пользователи ----
	CLIENT_CREATED,
	CLIENT_UPDATED,
	USER_CREATED,
	USER_UPDATED,

	// ---- база знаний ----
	KB_ARTICLE_CREATED,
	KB_ARTICLE_UPDATED,

	// ---- интеграции ----
	WEBHOOK_RECEIVED,                // входящий вебхук
	INTEGRATION_EVENT_RECEIVED       // event bus / сторонние события
}

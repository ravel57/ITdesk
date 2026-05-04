package ru.ravel.ItDesk.model.automatosation;

public enum TriggerType {
	MANUAL_MACRO_APPLIED,            // пользователь применил макрос

	// ---- сообщения (чат/комментарии) ----
	MESSAGE_INCOMING,                // входящее от клиента
	MESSAGE_OUTGOING,                // исходящее от агента
	MESSAGE_EDITED,                  // сообщение отредактировано
	MESSAGE_MENTIONED_USER,          // @упоминание пользователя
	MESSAGE_CONTAINS_KEYWORD,        // ключевое слово (можно как условие)
	MESSAGE_DELETED,
	ATTACHMENT_ADDED,

	// ---- SLA / таймеры ----
	SLA_WARNING,                     // скоро нарушится
	SLA_BREACHED,                    // нарушен
	SLA_STARTED,
	SLA_PAUSED,
	SLA_RESUMED,
	SLA_CANCELLED,
	SLA_COMPLETED,
	INACTIVITY_TIMEOUT,              // нет ответа X минут/часов
	SCHEDULED_CRON,                  // по расписанию (cron)

	// ---- задачи (если есть tasks) ----
	TASK_CREATED,
	TASK_UPDATED,                  // любое изменение (грубый триггер)
	TASK_STATUS_CHANGED,
	TASK_PRIORITY_CHANGED,
	TASK_ASSIGNEE_CHANGED,
	TASK_GROUP_CHANGED,
	TASK_TAG_ADDED,
	TASK_TAG_REMOVED,
	TASK_DUE_DATE_CHANGED,
	TASK_CLOSED,
	TASK_REOPENED,
	TASK_COMPLETED,
	TASK_OVERDUE,
	TASK_MESSAGE_MENTIONED_USER,
	TASK_COMMENT_ADDED,
	TASK_COMMENT_DELETED,
	TASK_EXECUTOR_CHANGED,

	// ---- клиенты ----
	CLIENT_CREATED,
	CLIENT_UPDATED,
	CLIENT_DELETED,

	// ---- пользователи ----
	USER_CREATED,
	USER_UPDATED,
	USER_OPEN_DIALOG,
	USER_CLOSED_DIALOG,

	// ---- база знаний ----
	KNOWLEDGE_BASE_ARTICLE_CREATED,
	KNOWLEDGE_BASE_ARTICLE_UPDATED,
	KNOWLEDGE_BASE_ARTICLE_DELETED,
	KNOWLEDGE_BASE_ARTICLE_PUBLISHED,

	// ---- интеграции ----
	WEBHOOK_RECEIVED,                // входящий вебхук
	INTEGRATION_EVENT_RECEIVED,      // event bus / сторонние события

	// ---- расписание ----
	WORKING_HOURS_STARTED,
	WORKING_HOURS_ENDED,

	// ---- автоматизации ----
	AUTOMATION_RULE_CREATED,
	AUTOMATION_RULE_UPDATED,
	AUTOMATION_RULE_DISABLED,
	AUTOMATION_RULE_FAILED,

	// ---- система ----
	SYSTEM_MAINTENANCE_STARTED,
	SYSTEM_MAINTENANCE_ENDED
}
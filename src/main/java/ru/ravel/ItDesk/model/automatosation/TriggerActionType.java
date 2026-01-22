package ru.ravel.ItDesk.model;

public enum TriggerActionType {
	// ---- изменение заявки ----
	SET_STATUS,
	SET_PRIORITY,
	SET_ASSIGNEE_USER,
	SET_ASSIGNEE_GROUP,
	CLEAR_ASSIGNEE,
	SET_DUE_DATE,
	CLEAR_DUE_DATE,

	// ---- теги ----
	ADD_TAG,
	REMOVE_TAG,
	REPLACE_TAGS,

	// ---- коммуникации ----
	ADD_INTERNAL_NOTE,               // внутренний комментарий
	ADD_PUBLIC_REPLY,                // ответ клиенту
	SEND_NOTIFICATION,               // уведомление (канал отдельно)
	SEND_EMAIL,
	SEND_TELEGRAM,
	SEND_WHATSAPP,
	SEND_WEB_PUSH,

	// ---- создание сущностей ----
	CREATE_TICKET,
	CREATE_TASK,

	// ---- связи ----
	LINK_TICKETS,
	MERGE_TICKETS,

	// ---- SLA ----
	SET_SLA_POLICY,
	PAUSE_SLA,
	RESUME_SLA,

	// ---- клиент ----
	UPDATE_CLIENT_FIELDS,            // телефон/email/имя/доп.поля

	// ---- кастомные поля ----
	SET_CUSTOM_FIELD,
	CLEAR_CUSTOM_FIELD,

	// ---- интеграции ----
	CALL_WEBHOOK,                    // исходящий webhook
	CALL_HTTP_REQUEST,               // универсальный HTTP action (если надо)
	EMIT_INTEGRATION_EVENT,          // внутренний event bus

	// ---- композиция ----
	RUN_MACRO,                       // выполнить макрос как действие
	RUN_RULE                         // выполнить другое правило (осторожно с циклами)
}

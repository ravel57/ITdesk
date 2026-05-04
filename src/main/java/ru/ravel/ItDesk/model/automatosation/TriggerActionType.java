package ru.ravel.ItDesk.model.automatosation;

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
	RUN_RULE,                         // выполнить другое правило (осторожно с циклами)

	CREATE_TASK_IF_NO_OPEN_TASKS,
	ADD_TAG_TO_OPEN_TASKS,
	REMOVE_TAG_FROM_OPEN_TASKS,
	SET_OPEN_TASKS_PRIORITY,
	SET_OPEN_TASKS_STATUS,

	CLOSE_TASK,
	REOPEN_TASK,
	DELETE_TASK,

	ADD_CLIENT_TAG,
	REMOVE_CLIENT_TAG,
	SET_CLIENT_ASSIGNEE,
	CLEAR_CLIENT_ASSIGNEE,
	BLOCK_CLIENT,
	UNBLOCK_CLIENT,

	SEND_CLIENT_MESSAGE,

	CREATE_KNOWLEDGE_ARTICLE,
	UPDATE_KNOWLEDGE_ARTICLE,

	WAIT,
	DELAY_UNTIL,
	STOP_RULES,
	STOP_PROCESSING,
	;
}
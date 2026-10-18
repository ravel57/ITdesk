package ru.ravel.ItDesk.model.automatosation;

/**
 * События, на которые срабатывают автоматизации. Названия для UI отдаются через GET /api/v1/trigger-types/options.
 */
public enum TriggerType {
	MANUAL_MACRO_APPLIED("Применён макрос", "Макросы"),            // пользователь применил макрос

	// ---- сообщения (чат/комментарии) ----
	MESSAGE_INCOMING("Входящее сообщение", "Сообщения"),                // входящее от клиента
	MESSAGE_OUTGOING("Исходящее сообщение", "Сообщения"),                // исходящее от агента
	MESSAGE_EDITED("Сообщение изменено", "Сообщения"),                  // сообщение отредактировано
	MESSAGE_MENTIONED_USER("Упоминание пользователя в сообщении", "Сообщения"),          // @упоминание пользователя
	MESSAGE_CONTAINS_KEYWORD("Сообщение содержит ключевое слово", "Сообщения"),        // ключевое слово (можно как условие)
	MESSAGE_DELETED("Сообщение удалено", "Сообщения"),
	ATTACHMENT_ADDED("Добавлено вложение", "Сообщения"),

	// ---- SLA / таймеры ----
	SLA_WARNING("Предупреждение SLA", "SLA и таймеры"),                     // скоро нарушится срок решения
	SLA_BREACHED("SLA нарушен", "SLA и таймеры"),                    // нарушен срок решения
	FIRST_RESPONSE_SLA_WARNING("Срок первого ответа истекает", "SLA и таймеры"),      // скоро нарушится срок первого ответа
	FIRST_RESPONSE_SLA_BREACHED("Срок первого ответа нарушен", "SLA и таймеры"),     // нарушен срок первого ответа
	SLA_STARTED("SLA запущен", "SLA и таймеры"),
	SLA_PAUSED("SLA приостановлен", "SLA и таймеры"),
	SLA_RESUMED("SLA возобновлён", "SLA и таймеры"),
	SLA_CANCELLED("SLA отменён", "SLA и таймеры"),
	SLA_COMPLETED("SLA выполнен", "SLA и таймеры"),
	INACTIVITY_TIMEOUT("Нет активности", "SLA и таймеры"),              // нет ответа X минут/часов
	SCHEDULED_CRON("По расписанию", "SLA и таймеры"),                  // по расписанию (cron)

	// ---- задачи (если есть tasks) ----
	TASK_CREATED("Заявка создана", "Заявки"),
	TASK_UPDATED("Заявка изменена", "Заявки"),                  // любое изменение (грубый триггер)
	TASK_STATUS_CHANGED("Изменён статус заявки", "Заявки"),
	TASK_PRIORITY_CHANGED("Изменён приоритет заявки", "Заявки"),
	TASK_TYPE_CHANGED("Изменён тип заявки", "Заявки"),                // изменён тип заявки
	TASK_SERVICE_CHANGED("Изменён сервис заявки", "Заявки"),             // изменён обслуживаемый сервис
	TASK_ASSIGNEE_CHANGED("Изменён исполнитель", "Заявки"),
	TASK_GROUP_CHANGED("Изменена линия поддержки", "Заявки"),
	TASK_TAG_ADDED("Добавлен тег", "Заявки"),
	TASK_TAG_REMOVED("Удалён тег", "Заявки"),
	TASK_DUE_DATE_CHANGED("Изменён срок заявки", "Заявки"),
	TASK_CLOSED("Заявка закрыта", "Заявки"),
	TASK_REOPENED("Заявка возвращена в работу", "Заявки"),
	TASK_OVERDUE("Заявка просрочена", "Заявки"),
	TASK_MESSAGE_MENTIONED_USER("Упоминание пользователя в заявке", "Заявки"),
	TASK_COMMENT_ADDED("Добавлен комментарий к заявке", "Заявки"),
	TASK_COMMENT_DELETED("Удалён комментарий к заявке", "Заявки"),
	TASK_EXECUTOR_CHANGED("Изменены исполнители заявки", "Заявки"),

	// ---- клиенты ----
	CLIENT_CREATED("Клиент создан", "Клиенты"),
	CLIENT_UPDATED("Клиент изменён", "Клиенты"),
	CLIENT_DELETED("Клиент удалён", "Клиенты"),

	// ---- пользователи ----
	USER_CREATED("Пользователь создан", "Пользователи"),
	USER_UPDATED("Пользователь изменён", "Пользователи"),
	USER_OPEN_DIALOG("Пользователь открыл диалог", "Пользователи"),
	USER_CLOSED_DIALOG("Пользователь закрыл диалог", "Пользователи"),

	// ---- база знаний ----
	KNOWLEDGE_BASE_ARTICLE_CREATED("Статья базы знаний создана", "База знаний"),
	KNOWLEDGE_BASE_ARTICLE_UPDATED("Статья базы знаний изменена", "База знаний"),
	KNOWLEDGE_BASE_ARTICLE_DELETED("Статья базы знаний удалена", "База знаний"),
	KNOWLEDGE_BASE_ARTICLE_PUBLISHED("Статья базы знаний опубликована", "База знаний"),

	// ---- мониторинг (Zabbix) ----
	MONITORING_PROBLEM_STARTED("Мониторинг: проблема", "Мониторинг"),
	MONITORING_PROBLEM_RESOLVED("Мониторинг: проблема устранена", "Мониторинг"),
	MONITORING_PROBLEM_UPDATED("Мониторинг: проблема обновлена", "Мониторинг"),
	MONITORING_CONNECTION_LOST("Мониторинг: нет связи с Zabbix", "Мониторинг"),

	// ---- интеграции ----
	WEBHOOK_RECEIVED("Получен вебхук", "Интеграции"),                // входящий вебхук
	INTEGRATION_EVENT_RECEIVED("Событие интеграции", "Интеграции"),      // event bus / сторонние события
	COMMUNICATION_SEND_REQUESTED("Запрос отправки во внешний канал", "Интеграции"),   // internal outbox command for external channel delivery

	// ---- расписание ----
	WORKING_HOURS_STARTED("Начало рабочего времени", "Рабочее время"),
	WORKING_HOURS_ENDED("Конец рабочего времени", "Рабочее время"),

	// ---- автоматизации ----
	AUTOMATION_RULE_CREATED("Правило автоматизации создано", "Автоматизации"),
	AUTOMATION_RULE_UPDATED("Правило автоматизации изменено", "Автоматизации"),
	AUTOMATION_RULE_DISABLED("Правило автоматизации отключено", "Автоматизации"),
	AUTOMATION_RULE_FAILED("Ошибка правила автоматизации", "Автоматизации"),

	// ---- система ----
	SYSTEM_MAINTENANCE_STARTED("Начало технических работ", "Система"),
	SYSTEM_MAINTENANCE_ENDED("Окончание технических работ", "Система");

	private final String label;
	private final String group;


	TriggerType(String label, String group) {
		this.label = label;
		this.group = group;
	}


	/** Название события для пользователя */
	public String label() {
		return label;
	}


	/** Раздел, к которому относится событие (заявки, сообщения, SLA…) */
	public String group() {
		return group;
	}
}
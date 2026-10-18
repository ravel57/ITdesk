-- Детектор Jev: режим «только лог» заменён на «только уведомлять» (у клиентов системы нет доступа к логам)
update app_settings set jev_incident_mode = 'NOTIFY_ONLY' where jev_incident_mode = 'LOG_ONLY';
alter table if exists app_settings alter column jev_incident_mode set default 'NOTIFY_ONLY';

-- Новое событие уведомлений INCIDENT_DETECTED: ограничение сгенерировано Hibernate и не обновляется само
alter table if exists user_notification_history drop constraint if exists user_notification_history_event_check;
alter table if exists user_notification_history add constraint user_notification_history_event_check check (
    event in (
        'MENTIONED_USER',
        'MENTIONED_USER_IN_TASK_CHAT',
        'NEW_TASK',
        'NEW_CHAT_MESSAGE',
        'SLA_HALF_TIME_PASSED',
        'SLA_OVERDUE',
        'CHAT_UNANSWERED_TOO_LONG',
        'DEADLINE_SOON',
        'DEADLINE_OVERDUE',
        'INCIDENT_UPDATE_OVERDUE',
        'INCIDENT_DETECTED'
    )
);

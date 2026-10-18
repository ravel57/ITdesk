-- События уведомлений MONITORING_CONNECTION_LOST / MONITORING_CONNECTION_RESTORED: связь с Zabbix потеряна и восстановлена.
-- Как и для событий автоматизаций (V49), список кодов в CHECK-ограничении приходилось обновлять на каждое новое
-- событие (V46). Коды проверяет enum UserNotificationEvent, поэтому ограничение снимается.
alter table if exists user_notification_history drop constraint if exists user_notification_history_event_check;

-- Роль «Старший оператор» (SENIOR_OPERATOR). Список ролей проверяет enum Role, поэтому
-- CHECK-ограничение, которое Hibernate создал по enum, снимается (как для событий в V49/V50).
alter table if exists user_authorities drop constraint if exists user_authorities_authorities_check;

-- Разделы, открытые менеджеру организации (OBSERVER): по умолчанию все, как было до настройки.
alter table app_settings
    add column if not exists observer_features jsonb not null default '["TASKS","INCIDENTS","ANALYTICS","SLA","SERVICES"]'::jsonb;

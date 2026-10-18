-- Журнал безопасности: входы, пароли, роли, удаления, выгрузки, ключи API, двухфакторный вход.
-- Пишется вместе с логгером SECURITY_AUDIT; страница «Журнал безопасности» доступна администратору.
create table if not exists security_audit_event
(
    id          bigserial primary key,
    created_at  timestamp(6) with time zone not null,
    type        varchar(64)  not null,
    actor       varchar(254),
    target      varchar(512),
    details     text,
    ip          varchar(64),
    user_agent  varchar(512)
);

create index if not exists idx_security_audit_event_created_at on security_audit_event (created_at desc);
create index if not exists idx_security_audit_event_type on security_audit_event (type, created_at desc);

-- Flyway выполняется после обновления схемы Hibernate (ddl-auto: update), и Hibernate мог уже создать таблицу
-- с CHECK-ограничением по enum: тогда новые типы событий не записались бы. Коды проверяет SecurityAuditEventType.
alter table security_audit_event drop constraint if exists security_audit_event_type_check;

-- Интеграция Zabbix в режиме веб-хука: Zabbix в закрытом контуре клиента сам отправляет события в ULDesk.

alter table if exists zabbix_integration
    add column if not exists mode varchar(16) not null default 'API_POLLING',
    add column if not exists webhook_key varchar(64),
    add column if not exists webhook_token_hash varchar(128),
    add column if not exists webhook_token_issued_at timestamptz,
    add column if not exists public_url varchar(1024),
    add column if not exists event_timezone varchar(64),
    add column if not exists allowed_ips varchar(1024),
    add column if not exists heartbeat_timeout_minutes integer not null default 15,
    add column if not exists last_event_at timestamptz,
    add column if not exists last_heartbeat_at timestamptz,
    add column if not exists connection_lost boolean not null default false;

-- В режиме веб-хука адрес Zabbix нужен только для ссылок, поэтому необязателен
alter table if exists zabbix_integration
    alter column base_url drop not null;

create unique index if not exists uk_zabbix_integration_webhook_key on zabbix_integration (webhook_key);

-- Проблемы Zabbix, полученные веб-хуком: по открытым считается состояние сервиса
create table if not exists monitoring_problem (
    id bigserial primary key,
    integration_id bigint not null references zabbix_integration (id) on delete cascade,
    event_id varchar(64) not null,
    service_id bigint references service (id) on delete set null,
    status varchar(16) not null,
    name varchar(1024),
    severity integer not null default 0,
    severity_name varchar(64),
    host varchar(255),
    host_name varchar(255),
    trigger_id varchar(64),
    acknowledged boolean not null default false,
    opdata varchar(1024),
    url varchar(2048),
    tags jsonb not null default '[]'::jsonb,
    started_at timestamptz,
    resolved_at timestamptz,
    received_at timestamptz not null,
    updated_at timestamptz not null,
    constraint uk_monitoring_problem_event unique (integration_id, event_id)
);

create index if not exists idx_monitoring_problem_service_status on monitoring_problem (service_id, status);
create index if not exists idx_monitoring_problem_integration_status on monitoring_problem (integration_id, status);

-- Журнал приёма веб-хуков: что пришло и к какому сервису привязалось
create table if not exists monitoring_webhook_log (
    id bigserial primary key,
    integration_id bigint not null references zabbix_integration (id) on delete cascade,
    received_at timestamptz not null,
    kind varchar(16) not null,
    event_id varchar(64),
    event_status varchar(16),
    event_name varchar(1024),
    severity integer,
    host varchar(255),
    service_id bigint,
    service_code varchar(80),
    outcome varchar(32) not null,
    message varchar(1024),
    remote_ip varchar(64)
);

create index if not exists idx_monitoring_webhook_log_integration on monitoring_webhook_log (integration_id, received_at desc);

-- CHECK-ограничения на коды событий, созданные Hibernate, ломают каждое новое событие автоматизаций
-- (см. V18, V19, V25, V31). Коды проверяет enum TriggerType в приложении, поэтому ограничения снимаются.
alter table if exists event drop constraint if exists event_trigger_type_check;
alter table if exists automation_trigger drop constraint if exists automation_trigger_trigger_type_check;
alter table if exists automation_workflow_run drop constraint if exists automation_workflow_run_trigger_type_check;
alter table if exists automation_workflow_run drop constraint if exists automation_workflow_run_wait_event_type_check;

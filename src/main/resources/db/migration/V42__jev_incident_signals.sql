-- Сигналы детектора массовых инцидентов: разметка входящих сообщений Jev
create table if not exists incident_signal (
    id                     bigserial primary key,
    message_id             bigint           not null,
    client_id              bigint           not null,
    organization_id        bigint,
    service_id             bigint,
    symptom                varchar(64),
    outage_probability     double precision not null,
    impact_score           double precision,
    matched_incident_id    bigint,
    decision               varchar(32)      not null,
    created_at             timestamptz      not null
);

create index if not exists idx_incident_signal_window
    on incident_signal (service_id, symptom, created_at);

create index if not exists idx_incident_signal_message
    on incident_signal (message_id);

-- Настройки детектора (страница Настройки → Jev)
alter table if exists app_settings
    add column if not exists jev_incident_window_minutes integer not null default 15,
    add column if not exists jev_incident_min_clients integer not null default 3,
    add column if not exists jev_incident_outage_threshold double precision not null default 0.7,
    add column if not exists jev_incident_cooldown_minutes integer not null default 60;

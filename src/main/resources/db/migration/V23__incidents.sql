create table if not exists support_incident (
    id bigserial primary key,
    incident_number varchar(32) unique,
    title varchar(512) not null,
    description text,
    public_description text,
    impact_summary text,
    service_name varchar(255),
    workaround text,
    root_cause text,
    resolution text,
    postmortem_summary text,
    severity varchar(16) not null default 'P3',
    status varchar(32) not null default 'DRAFT',
    leader_id bigint references user_t(id),
    leader_assigned_at timestamptz,
    technical_owner_id bigint references user_t(id),
    communication_owner_id bigint references user_t(id),
    support_line_id bigint references support_line(id),
    created_by_id bigint references user_t(id),
    actual_started_at timestamptz,
    detected_at timestamptz,
    declared_at timestamptz,
    mitigated_at timestamptz,
    restored_at timestamptz,
    closed_at timestamptz,
    next_update_at timestamptz,
    last_published_update_at timestamptz,
    communication_overdue_notified_at timestamptz,
    update_interval_minutes integer not null default 30,
    planned_updates_count integer not null default 0,
    on_time_updates_count integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists support_incident_participants (
    incident_id bigint not null references support_incident(id) on delete cascade,
    user_id bigint not null references user_t(id),
    primary key (incident_id, user_id)
);

create table if not exists support_incident_observers (
    incident_id bigint not null references support_incident(id) on delete cascade,
    user_id bigint not null references user_t(id),
    primary key (incident_id, user_id)
);

create table if not exists support_incident_organizations (
    incident_id bigint not null references support_incident(id) on delete cascade,
    organization_id bigint not null references organization(id),
    primary key (incident_id, organization_id)
);

create table if not exists support_incident_clients (
    incident_id bigint not null references support_incident(id) on delete cascade,
    client_id bigint not null references client(id),
    primary key (incident_id, client_id)
);

create table if not exists support_incident_task_link (
    id bigserial primary key,
    incident_id bigint not null references support_incident(id) on delete cascade,
    task_id bigint not null references task(id),
    linked_by_id bigint references user_t(id),
    linked_at timestamptz not null default now(),
    primary_task boolean not null default false,
    recovery_confirmed boolean not null default false,
    recovery_confirmed_at timestamptz,
    recovery_confirmed_by_id bigint references user_t(id),
    active boolean not null default true,
    unlinked_at timestamptz,
    unlinked_by_id bigint references user_t(id)
);

create index if not exists idx_support_incident_task_link_incident on support_incident_task_link(incident_id, active);
create index if not exists idx_support_incident_task_link_task on support_incident_task_link(task_id, active);
create unique index if not exists uq_support_incident_task_active
    on support_incident_task_link(task_id)
    where active = true;

create table if not exists support_incident_timeline_entry (
    id bigserial primary key,
    incident_id bigint not null references support_incident(id) on delete cascade,
    type varchar(32) not null,
    audience varchar(32) not null,
    text text not null,
    author_id bigint references user_t(id),
    created_at timestamptz not null default now(),
    template_key varchar(64)
);

create index if not exists idx_support_incident_timeline_entry_incident
    on support_incident_timeline_entry(incident_id, created_at, id);
create index if not exists idx_support_incident_active_next_update
    on support_incident(status, next_update_at);

alter table automation_trigger
    add column if not exists workflow_definition text;

alter table automation_trigger
    add column if not exists workflow_version integer not null default 1;

alter table automation_trigger
    add column if not exists failure_count bigint not null default 0;

alter table automation_trigger
    add column if not exists last_failed_at timestamp with time zone;

alter table automation_trigger
    add column if not exists last_error varchar(2000);

create table if not exists automation_workflow_run (
    id bigserial primary key,
    trigger_id bigint not null,
    source_event_id bigint,
    trigger_type varchar(100) not null,
    event_payload jsonb not null,
    workflow_definition text,
    current_node_id varchar(255) not null,
    status varchar(32) not null,
    step_count integer not null default 0,
    retries integer not null default 0,
    available_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    completed_at timestamp with time zone,
    last_error varchar(2000),
    actor_user_id bigint,
    actor_username varchar(255),
    actor_display_name varchar(255),
    actor_type varchar(32)
);

create index if not exists idx_automation_workflow_run_due
    on automation_workflow_run(status, available_at);

create index if not exists idx_automation_workflow_run_trigger
    on automation_workflow_run(trigger_id, created_at desc);

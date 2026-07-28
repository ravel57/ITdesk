alter table automation_workflow_run
    add column if not exists wait_kind varchar(40),
    add column if not exists wait_event_type varchar(100),
    add column if not exists correlation_key varchar(500),
    add column if not exists resume_node_id varchar(255),
    add column if not exists rejected_node_id varchar(255),
    add column if not exists timeout_node_id varchar(255),
    add column if not exists wait_expression text,
    add column if not exists approver_user_id bigint,
    add column if not exists approval_title varchar(500),
    add column if not exists approval_message text,
    add column if not exists decision_comment text;

create index if not exists idx_automation_workflow_run_wait_event
    on automation_workflow_run(status, wait_event_type, correlation_key);

create table if not exists automation_throttle_record (
    id bigserial primary key,
    trigger_id bigint not null,
    node_id varchar(255) not null,
    scope_key varchar(500) not null,
    last_executed_at timestamp with time zone not null,
    constraint uk_automation_throttle unique(trigger_id, node_id, scope_key)
);

create index if not exists idx_automation_throttle_last_executed
    on automation_throttle_record(last_executed_at);

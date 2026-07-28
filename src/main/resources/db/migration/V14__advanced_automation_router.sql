alter table automation_trigger
    add column if not exists stop_processing boolean not null default false;

alter table automation_trigger
    add column if not exists match_count bigint not null default 0;

alter table automation_trigger
    add column if not exists last_matched_at timestamp with time zone;

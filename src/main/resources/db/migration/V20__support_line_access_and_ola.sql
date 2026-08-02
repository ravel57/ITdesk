alter table app_settings
    add column if not exists support_line_access_mode varchar(32) not null default 'HYBRID';

alter table support_line
    add column if not exists responsible_user_id bigint references user_t(id),
    add column if not exists assignment_strategy varchar(32) not null default 'KEEP_UNASSIGNED',
    add column if not exists visibility_mode varchar(40) not null default 'INHERIT',
    add column if not exists allow_self_assignment boolean not null default true,
    add column if not exists notify_on_new_task boolean not null default true,
    add column if not exists capacity_per_member integer not null default 0,
    add column if not exists round_robin_cursor bigint,
    add column if not exists ola_enabled boolean not null default false,
    add column if not exists ola_value integer,
    add column if not exists ola_unit varchar(24) not null default 'HOURS',
    add column if not exists ola_warning_percent integer not null default 80,
    add column if not exists ola_use_working_time boolean not null default true;

create table if not exists support_line_observers (
    support_line_id bigint not null references support_line(id) on delete cascade,
    user_id bigint not null references user_t(id) on delete cascade,
    primary key (support_line_id, user_id)
);

create index if not exists idx_support_line_responsible_user_id
    on support_line(responsible_user_id);

create index if not exists idx_support_line_members_user_id
    on support_line_members(user_id, support_line_id);

create index if not exists idx_support_line_observers_user_id
    on support_line_observers(user_id, support_line_id);

-- Сохраняем прежнюю доступность существующих линий: если команда линии ещё не
-- настроена, добавляем в неё действующих операторов из текущей модели ролей.
insert into support_line_members(support_line_id, user_id)
select sl.id, ua.user_id
from support_line sl
join user_authorities ua on ua.authorities = 'OPERATOR'
join user_t u on u.id = ua.user_id and coalesce(u.is_enabled, true) = true
where not exists (
    select 1
    from support_line_members existing
    where existing.support_line_id = sl.id
)
on conflict do nothing;

-- Сохраняем инвариант «исполнитель входит в текущую линию» для уже созданных заявок.
insert into support_line_members(support_line_id, user_id)
select distinct t.support_line_id, t.executor_id
from task t
join user_t u on u.id = t.executor_id
where t.support_line_id is not null
  and t.executor_id is not null
on conflict do nothing;

alter table task
    add column if not exists entered_current_line_at timestamp with time zone,
    add column if not exists ola_deadline timestamp with time zone,
    add column if not exists ola_warning_at timestamp with time zone,
    add column if not exists ola_status varchar(24) not null default 'DISABLED',
    add column if not exists ola_duration_seconds bigint,
    add column if not exists ola_use_working_time boolean not null default false,
    add column if not exists ola_paused_at timestamp with time zone,
    add column if not exists ola_remaining_seconds_on_pause bigint;

update task
set entered_current_line_at = coalesce(created_at, now())
where support_line_id is not null
  and entered_current_line_at is null;

create table if not exists task_access_users (
    task_id bigint not null references task(id) on delete cascade,
    user_id bigint not null references user_t(id) on delete cascade,
    primary key (task_id, user_id)
);

create index if not exists idx_task_access_users_user_id
    on task_access_users(user_id, task_id);

-- Пользователи, которых уже упоминали в чатах заявок, должны сохранить доступ
-- после включения гибридной модели, даже если индикатор пинга уже был прочитан.
insert into task_access_users(task_id, user_id)
select t.id, ping.key::bigint
from task t
cross join lateral jsonb_each_text(coalesce(t.unread_ping_tasks_messages::jsonb, '{}'::jsonb)) ping
join user_t u on u.id = case when ping.key ~ '^[0-9]+$' then ping.key::bigint else null end
where ping.key ~ '^[0-9]+$'
on conflict do nothing;

create index if not exists idx_task_ola_deadline
    on task(ola_deadline)
    where ola_deadline is not null;

create index if not exists idx_task_ola_warning_at
    on task(ola_warning_at)
    where ola_warning_at is not null;

create table if not exists task_support_line_stage (
    id bigserial primary key,
    task_id bigint not null references task(id) on delete cascade,
    support_line_id bigint references support_line(id),
    entered_at timestamp with time zone not null,
    left_at timestamp with time zone,
    ola_deadline timestamp with time zone,
    ola_warning_at timestamp with time zone,
    breached_at timestamp with time zone,
    status varchar(24) not null default 'DISABLED',
    duration_seconds bigint,
    use_working_time boolean not null default false,
    paused_seconds bigint not null default 0,
    transferred_by_user_id bigint references user_t(id),
    transfer_reason varchar(1024)
);

create index if not exists idx_task_support_line_stage_task
    on task_support_line_stage(task_id, entered_at);

create index if not exists idx_task_support_line_stage_line
    on task_support_line_stage(support_line_id, entered_at);

create unique index if not exists uk_task_support_line_stage_active
    on task_support_line_stage(task_id)
    where left_at is null;

insert into task_support_line_stage (
    task_id,
    support_line_id,
    entered_at,
    left_at,
    status,
    use_working_time,
    paused_seconds,
    transfer_reason
)
select
    t.id,
    t.support_line_id,
    coalesce(t.entered_current_line_at, t.created_at, now()),
    case when coalesce(t.completed, false) then coalesce(t.closed_at, t.last_activity, now()) else null end,
    case when coalesce(t.completed, false) then 'COMPLETED' else 'DISABLED' end,
    false,
    0,
    'Начальное состояние после миграции'
from task t
where t.support_line_id is not null
  and not exists (
      select 1
      from task_support_line_stage s
      where s.task_id = t.id
  );

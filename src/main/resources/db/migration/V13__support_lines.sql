create table if not exists support_line (
    id bigserial primary key,
    name varchar(255) not null unique,
    description varchar(1024) not null default '',
    level integer not null default 1,
    active boolean not null default true,
    default_selection boolean not null default false,
    order_number integer not null default 0
);

create table if not exists support_line_members (
    support_line_id bigint not null references support_line(id) on delete cascade,
    user_id bigint not null references user_t(id) on delete cascade,
    primary key (support_line_id, user_id)
);

alter table task
    add column if not exists support_line_id bigint references support_line(id);

create index if not exists idx_task_support_line_id on task(support_line_id);

insert into support_line(name, description, level, active, default_selection, order_number)
select 'Первая линия', 'Линия поддержки по умолчанию', 1, true, true, 0
where not exists (select 1 from support_line);

update task
set support_line_id = (select id from support_line where default_selection = true order by order_number limit 1)
where support_line_id is null;

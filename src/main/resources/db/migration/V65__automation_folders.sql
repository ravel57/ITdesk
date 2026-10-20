-- Папки автоматизаций: чисто организационная группировка. Порядок выполнения по-прежнему задаёт automation_trigger.order_number.
-- Flyway выполняется после обновления схемы Hibernate (ddl-auto: update), который мог уже создать таблицу и колонки
-- без внешних ключей, поэтому скрипт идемпотентен.
create table if not exists automation_folder (
    id bigserial primary key,
    name varchar(255) not null,
    collapsed boolean not null default false,
    display_order integer not null default 0,
    parent_id bigint
);

alter table automation_folder add column if not exists parent_id bigint;
alter table automation_trigger add column if not exists folder_id bigint;
alter table automation_trigger add column if not exists display_order integer not null default 0;

do $$
begin
    if not exists (select 1 from pg_constraint where conname = 'fk_automation_folder_parent') then
        alter table automation_folder add constraint fk_automation_folder_parent
            foreign key (parent_id) references automation_folder (id) on delete set null;
    end if;
    if not exists (select 1 from pg_constraint where conname = 'fk_automation_trigger_folder') then
        alter table automation_trigger add constraint fk_automation_trigger_folder
            foreign key (folder_id) references automation_folder (id) on delete set null;
    end if;
end $$;

-- Существующие правила остаются на верхнем уровне в прежнем порядке.
update automation_trigger set display_order = coalesce(order_number, 0) where folder_id is null;

create index if not exists idx_automation_folder_parent on automation_folder (parent_id);
create index if not exists idx_automation_trigger_folder on automation_trigger (folder_id);

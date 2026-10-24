-- Подсказки оператору без привязки к названию правила.
-- Источник — свободный ключ из графа (article, task, reply или свой) с названием для статистики; название правила
-- сохраняется на момент публикации, чтобы статистика удалённого правила не терялась. Комментарий с подсказкой
-- помечается id подсказки: по метке чат показывает кнопки оценки, а не по тексту комментария.
-- Hibernate (ddl-auto) добавляет колонки раньше Flyway, поэтому всё идемпотентно.
alter table if exists operator_hint drop constraint if exists operator_hint_source_check;
alter table operator_hint alter column source type varchar(64);
alter table operator_hint add column if not exists source_label varchar(255);
alter table operator_hint add column if not exists rule_name varchar(255);
update operator_hint set source = lower(source) where source <> lower(source);
update operator_hint
set source_label = case source
                       when 'article' then 'Статьи базы знаний'
                       when 'task' then 'Закрытые заявки'
                       when 'reply' then 'История чатов'
                       else source end
where source_label is null;
update operator_hint h
set rule_name = t.name
from automation_trigger t
where t.id = h.rule_id
  and h.rule_name is null;

alter table message add column if not exists operator_hint_id bigint;
update message m
set operator_hint_id = h.id
from operator_hint h
where h.note_message_id = m.id
  and m.operator_hint_id is null;

-- Графы с шагом «Опубликовать подсказку оператору»: название источника передаётся из графа.
-- Для графа конвейера подсказок (материал источника в var.material) названия добавляются в шаги «Материал: …».
-- Узлы ищутся по операции и переменной, а не по названию правила; исходный граф сохраняется в резервную копию.
do $migration$
    declare
        trigger_row automation_trigger%rowtype;
        graph       jsonb;
        patched     jsonb;
    begin
        for trigger_row in select * from automation_trigger where workflow_definition like '%hint.publish%'
            loop
                graph := trigger_row.workflow_definition::jsonb;
                select jsonb_agg(case
                                     when node -> 'config' ->> 'operation' = 'hint.publish'
                                         and not (node -> 'config' -> 'arguments' ? 'sourceLabel')
                                         then jsonb_set(node, '{config,arguments,sourceLabel}', '"=var.material.sourceLabel"')
                                     when node -> 'config' ->> 'operation' = 'value.set'
                                         and node -> 'config' ->> 'resultVariable' = 'material'
                                         and jsonb_typeof(node -> 'config' -> 'arguments' -> 'value') = 'object'
                                         and not (node -> 'config' -> 'arguments' -> 'value' ? 'sourceLabel')
                                         and node -> 'config' -> 'arguments' -> 'value' ->> 'kind' in ('article', 'task', 'reply')
                                         then jsonb_set(node, '{config,arguments,value,sourceLabel}',
                                                        to_jsonb(case node -> 'config' -> 'arguments' -> 'value' ->> 'kind'
                                                                     when 'article' then 'Статьи базы знаний'
                                                                     when 'task' then 'Закрытые заявки'
                                                                     else 'История чатов' end))
                                     else node end order by position)
                into patched
                from jsonb_array_elements(graph -> 'nodes') with ordinality as nodes(node, position);
                if patched is not null and patched is distinct from graph -> 'nodes' then
                    insert into automation_workflow_backup(trigger_id, migration_version, definition)
                    values (trigger_row.id, 77, to_jsonb(trigger_row))
                    on conflict do nothing;
                    update automation_trigger
                    set workflow_definition = jsonb_set(graph, '{nodes}', patched)::text
                    where id = trigger_row.id;
                end if;
            end loop;
    end
$migration$;

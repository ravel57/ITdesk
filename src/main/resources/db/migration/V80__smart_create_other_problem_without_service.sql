-- Заявки по «Прочей проблеме» умного создания остаются без сервиса, даже если у организации один сервис.
-- Признак task.no_default_service: сервис организации по умолчанию (единственный или основной) не подставляется
-- ни при создании, ни при изменениях, пока сервис не выберут явно. Hibernate (ddl-auto) может добавить колонку раньше.
alter table task add column if not exists no_default_service boolean not null default false;

-- Шаг «Создать заявку по этой проблеме»: для проблемы без сервиса (вариант «Прочая проблема») — без сервиса по умолчанию.
-- Правится только этот аргумент узла шаблона; исходный граф — в резервной копии.
do $migration$
declare
    target record;
    graph jsonb;
    nodes jsonb;
begin
    for target in select * from automation_trigger
        where workflow_definition is not null
          and workflow_definition::jsonb->>'entryNodeId' = 'smart-create-1'
          and exists (select 1 from jsonb_array_elements(workflow_definition::jsonb->'nodes') n
                      where n->>'id' = 'smart-create-57'
                        and n->'config'->>'operation' = 'task.create'
                        and not (n->'config'->'arguments' ? 'noDefaultService'))
        for update
    loop
        insert into automation_workflow_backup(trigger_id, migration_version, definition)
            values (target.id, 80, to_jsonb(target)) on conflict do nothing;
        graph := target.workflow_definition::jsonb;
        select jsonb_agg(case
                             when node->>'id' = 'smart-create-57'
                                 then jsonb_set(node, '{config,arguments,noDefaultService}', '"=var.problem.id.isNull()"')
                             else node end order by position)
        into nodes
        from jsonb_array_elements(graph->'nodes') with ordinality as items(node, position);
        update automation_trigger set workflow_definition = jsonb_set(graph, '{nodes}', nodes)::text where id = target.id;
    end loop;
end
$migration$;

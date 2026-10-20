-- Unified Jev journal: request/question status is separate from decisions made by graph conditions.
-- Hibernate schema update may already have created these columns before Flyway runs.
alter table jev_decision add column if not exists request_id varchar(255);
alter table jev_decision add column if not exists source varchar(255);
alter table jev_decision add column if not exists response_status varchar(255);
alter table jev_decision add column if not exists event_id bigint;
alter table jev_decision add column if not exists execution_id varchar(255);
alter table jev_decision add column if not exists node_id varchar(255);
alter table jev_decision add column if not exists node_label varchar(255);
alter table jev_decision add column if not exists question_title varchar(1024);
alter table jev_decision add column if not exists answer_type varchar(255);
alter table jev_decision add column if not exists error_message varchar(1000);
alter table jev_decision add column if not exists trace_id bigint;
alter table jev_decision alter column confidence drop not null;

update jev_decision set source = coalesce(source, 'LEGACY'), response_status = coalesce(response_status, 'RECORDED')
where source is null or response_status is null;

create unique index if not exists ux_jev_decision_trace on jev_decision(trace_id);
create index if not exists idx_jev_decision_graph on jev_decision(rule_id, node_id, created_at desc);
create index if not exists idx_jev_decision_request on jev_decision(request_id);

-- ai.decide used to bypass JevDecisionRecorder. Recover the results that are still present in graph traces.
-- signal.analyze/verify already wrote legacy per-question decisions; importing them would duplicate history.
insert into jev_decision(created_at,rule_id,rule_name,action,context,question,choice,confidence,threshold,outcome,
                         request_id,source,response_status,execution_id,node_id,node_label,question_title,answer_type,error_message,trace_id)
select t.created_at,t.trigger_id,a.name,'ai.decide','automation=' || t.trigger_id,'decision',
       left(case when t.step#>>'{output,choice}'='__none__' then 'Ни один вариант'
                 when t.step#>>'{input,kind}'='noul' then 'Вероятность ответа «Да»'
                 else coalesce(t.step#>>'{output,value,name}',t.step#>>'{output,value,title}',
                      t.step#>'{input,options}' ->> (t.step#>>'{output,choice}'),t.step#>>'{output,choice}') end,255),
       case when jsonb_typeof(t.step#>'{output,probability}')='number' then (t.step#>>'{output,probability}')::double precision end,
       null,
       case when t.step->>'status'='FAILED' then 'FAILED'
            when t.step#>>'{output,status}'='NO_OPTIONS' then 'SKIPPED'
            when t.step#>>'{output,status}'='INVALID_RESPONSE' then 'INVALID'
            when t.step#>>'{output,status}'='UNAVAILABLE' then 'FAILED'
            when t.step#>>'{output,choice}'='__none__' then 'NONE' else 'ANSWERED' end,
       'trace:' || t.id,'GRAPH_TRACE',
       case when t.step->>'status'='FAILED' then 'ERROR' else coalesce(t.step#>>'{output,status}','OK') end,
       t.execution_id,left(t.step->>'nodeId',255),left(t.step->>'label',255),
       left(coalesce(t.step#>>'{input,question}',t.step->>'label'),1024),coalesce(t.step#>>'{input,kind}','choice'),
       case when t.step->>'status'='FAILED' then 'Операция анализа завершилась ошибкой; подробности в журнале графа'
            when t.step#>>'{output,status}'='UNAVAILABLE' then 'Ответ Jev недоступен; причина не сохранена в прежней трассировке'
            when t.step#>>'{output,status}'='INVALID_RESPONSE' then 'Ответ Jev не соответствует типу вопроса'
            when t.step#>>'{output,status}'='NO_OPTIONS' then 'Нет вариантов для выбора; запрос Jev не отправлялся' end,
       t.id
from automation_graph_trace t
left join automation_trigger a on a.id=t.trigger_id
left join lateral (
    select n as definition from jsonb_array_elements(coalesce(nullif(a.workflow_definition,''),'{}')::jsonb->'nodes') n
    where n->>'id'=t.step->>'nodeId' limit 1
) node on true
where t.step->>'detail'='ai.decide'
   or (t.step->>'status'='FAILED' and node.definition#>>'{config,operation}'='ai.decide')
on conflict (trace_id) do nothing;

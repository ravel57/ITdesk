create index concurrently if not exists idx_message_client_date_id_desc
    on message (client_id, date desc nulls last, id desc);

create index concurrently if not exists idx_task_client_linked_message_id
    on task (client_id, linked_message_id)
    where linked_message_id is not null;
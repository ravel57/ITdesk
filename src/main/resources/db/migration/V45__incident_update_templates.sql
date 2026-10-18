-- Шаблоны обновлений инцидента (раньше были захардкожены в IncidentService.getUpdateTemplates)
create table if not exists incident_update_template (
    id           bigserial primary key,
    template_key varchar(64)  not null,
    title        varchar(255) not null,
    text         text         not null,
    order_number integer      not null default 0,
    constraint uk_incident_update_template_key unique (template_key)
);

insert into incident_update_template (template_key, title, text, order_number)
values ('investigating', 'Исследуем',
        'Мы подтвердили наличие проблемы и проводим диагностику. Следующее обновление будет опубликовано в указанное время.', 0),
       ('identified', 'Причина определена',
        'Причина проблемы определена. Специалисты выполняют восстановительные работы.', 1),
       ('mitigating', 'Выполняется восстановление',
        'Выполняются действия по восстановлению сервиса. Влияние на пользователей постепенно снижается.', 2),
       ('monitoring', 'Наблюдение',
        'Работоспособность сервиса восстановлена. Мы наблюдаем за стабильностью и проверяем отсутствие повторных сбоев.', 3),
       ('restored', 'Восстановлено',
        'Сервис восстановлен и работает стабильно. Продолжаем анализ причин и подготовку профилактических мероприятий.', 4)
on conflict (template_key) do nothing;

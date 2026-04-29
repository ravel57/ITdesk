INSERT INTO public.user_t (id, firstname, lastname, is_account_non_expired, is_account_non_locked,
                           is_credentials_non_expired, is_enabled, password, username, type)
VALUES (DEFAULT, 'system', 'user (ULDESK)', false, false, false, false, '', 'Система (ULDESK)', 'SystemUser'),
       (DEFAULT, 'admin', 'admin', true, true, true, true,
        '$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.', 'admin', 'User');

INSERT INTO public.user_authorities (user_id, authorities)
VALUES (2, 'ADMIN');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'Срочно'),
       (DEFAULT, null, 'Выезд'),
       (DEFAULT, null, 'Отложенно'),
       (DEFAULT, null, 'VIP'),
       (DEFAULT, null, 'Просрочено'),
       (DEFAULT, null, 'SLA скоро нарушится'),
       (DEFAULT, null, 'SLA нарушен');

INSERT INTO public.status (id, order_number, name, default_selection, type)
VALUES (DEFAULT, 1, 'Новая', true, 'Status');

INSERT INTO public.status (id, order_number, name, type)
VALUES (DEFAULT, 2, 'В работе', 'Status'),
       (DEFAULT, 3, 'Решена', 'Status'),
       (DEFAULT, 4, 'Клиент не отвечает', 'Status'),
       (DEFAULT, 999, 'Заморожена', 'FrozenStatus'),
       (DEFAULT, 1000, 'Закрыта', 'CompletedStatus');

INSERT INTO public.priority (id, order_number, name, critical, default_selection)
VALUES (DEFAULT, 1, 'Критичный', true, false),
       (DEFAULT, 2, 'Высокий', false, false),
       (DEFAULT, 3, 'Средний', false, true),
       (DEFAULT, 4, 'Низкий', false, false),
       (DEFAULT, 5, 'Приостановленно', false, false);

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Добрый день!', 'дд'),
       (DEFAULT, 'Доброе утро!', 'ду'),
       (DEFAULT, 'Добрый вечер!', 'дв'),
       (DEFAULT, 'Уже решаем', 'решаем');

INSERT INTO automation_trigger(action, automation_rule_status, description, expression, name, order_number,
                               trigger_type)
VALUES ('client.sendMessage(''Здравствуйте! Это автоответ. Вам напишет первый освободившийся оператор.'')', 'ENABLED',
        'Автоматически отправляет приветственное сообщение новому клиенту.', 'true', 'Приветственное сообщение', 0,
        'CLIENT_CREATED'),
       ('task.create(message.text)', 'ENABLED',
        'Автоматически создает новую заявку по первому входящему сообщению, если у клиента нет открытых задач.',
        'client.openTasks.size() = 0 and client.incomeMessages.size() > 1', 'Авто-новая заявка', 1, 'MESSAGE_INCOMING'),
       ('task.addTag(''Просрочено'')', 'ENABLED', 'Добавляет тег Просрочено к задаче, если задача стала просроченной.',
        'true', 'Просроченная задача', 2, 'TASK_OVERDUE'),
       ('client.sendMessage(''Здравствуйте! Мы получили ваше сообщение. Сейчас нерабочее время, оператор ответит вам в ближайшее рабочее время.'')',
        'ENABLED', 'Автоматически отвечает клиенту, если сообщение пришло вне рабочего времени.',
        'is_after_hours(now())', 'Сообщение вне рабочего времени', 3, 'MESSAGE_INCOMING'),
       ('task.addTag(''SLA скоро нарушится'')', 'ENABLED', 'Добавляет тег к задаче, если SLA скоро будет нарушен.',
        'true', 'SLA скоро нарушится', 4, 'SLA_WARNING'),
       ('task.addTag(''SLA нарушен'')', 'ENABLED', 'Добавляет тег к задаче, если SLA был нарушен.', 'true',
        'SLA нарушен', 5, 'SLA_BREACHED');
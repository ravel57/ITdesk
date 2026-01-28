INSERT INTO public.user_t (id, firstname, lastname, is_account_non_expired, is_account_non_locked, is_credentials_non_expired, is_enabled, password, username, type)
VALUES (DEFAULT, 'system', 'user (ULDESK)', false, false, false, false, '', 'Система (ULDESK)', 'SystemUser'),
(DEFAULT, 'admin', 'admin', true, true, true, true, '$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.', 'admin', 'User');

INSERT INTO public.user_authorities (user_id, authorities)
VALUES (2, 'ADMIN');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'ЭЦП');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'Принтер');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'Срочно');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'ВКС');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'Выезд');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'Отложенно');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'Инфроструктурное оборудование');

INSERT INTO public.tag (id, description, name)
VALUES (DEFAULT, null, 'VIP');

INSERT INTO public.status (id, order_number, name, default_selection, type)
VALUES (DEFAULT, 1, 'Новая', true, 'Status');

INSERT INTO public.status (id, order_number, name, type)
VALUES (DEFAULT, 2, 'В работе', 'Status');

INSERT INTO public.status (id, order_number, name, type)
VALUES (DEFAULT, 3, 'Решена', 'Status');

INSERT INTO public.status (id, order_number, name, type)
VALUES (DEFAULT, 4, 'Клиент не отвечает', 'Status');

INSERT INTO public.status (id, order_number, name, type)
VALUES (DEFAULT, 999, 'Заморожена', 'FrozenStatus');

INSERT INTO public.status (id, order_number, name, type)
VALUES (DEFAULT, 1000, 'Закрыта', 'CompletedStatus');

INSERT INTO public.priority (id, order_number, name, critical)
VALUES (DEFAULT, 1, 'Критичный', true);

INSERT INTO public.priority (id, order_number, name)
VALUES (DEFAULT, 2, 'Высокий');

INSERT INTO public.priority (id, order_number, name, default_selection)
VALUES (DEFAULT, 3, 'Средний', true);

INSERT INTO public.priority (id, order_number, name)
VALUES (DEFAULT, 4, 'Низкий');

INSERT INTO public.priority (id, order_number, name)
VALUES (DEFAULT, 5, 'Приостановленно');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Добрый день!', 'дд');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Доброе утро!', 'ду');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Добрый вечер!', 'дв');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Пришлите код от anydesk', 'эни');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Примите подключение', 'подключ');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Сотрудник в пути', 'впути');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Уже решаем', 'решаем');

INSERT INTO automation_trigger(action, automation_rule_status, description, expression, name, order_number, trigger_type)
VALUES ('client.sendMessage(''Здравствуйте! Это автоответ. Вам напишет первый освободившийся оператор.'')', 'ENABLED', '', 'true', 'Приветственное сообщение', 0, 'CLIENT_CREATED'),
('task.create(message.text)', 'ENABLED', '', 'client.openTasks.size() = 0 and client.incomeMessages.size() > 1', 'Авто-новая заявка', 2, 'MESSAGE_INCOMING');
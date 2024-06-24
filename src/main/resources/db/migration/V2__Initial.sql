INSERT INTO public.t_user (id, firstname, lastname, is_account_non_expired, is_account_non_locked, is_credentials_non_expired, is_enabled, password, username)
VALUES (DEFAULT, 'admin', 'admin', true, true, true, true, '$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.', 'admin');

INSERT INTO public.user_authorities (user_id, authorities)
VALUES (1, 0);

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

INSERT INTO public.status (id, order_number, name, default_selection)
VALUES (DEFAULT, 1, 'Новая', true);

INSERT INTO public.status (id, order_number, name)
VALUES (DEFAULT, 2, 'В работе');

INSERT INTO public.status (id, order_number, name)
VALUES (DEFAULT, 3, 'Решена');

INSERT INTO public.priority (id, order_number, name, critical)
VALUES (DEFAULT, 1, 'Критичный', true);

INSERT INTO public.priority (id, order_number, name)
VALUES (DEFAULT, 2, 'Высокий');

INSERT INTO public.priority (id, name, order_number, default_selection)
VALUES (DEFAULT, 'Средний', 3, true);

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

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Не наша зона ответственности', 'немы');

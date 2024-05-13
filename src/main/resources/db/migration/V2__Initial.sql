-- INSERT INTO public.t_user (id, firstname, is_account_non_expired, is_account_non_locked, is_credentials_non_expired, is_enabled, lastname, password, username)
-- VALUES (DEFAULT, 'admin', true, true, true, true, null, '$2a$12$qzyw1.HJ4TIKvq8Z.Vdt6uwKRTvimL9V6h53u.s/DyoqDEVuML1j.', 'admin');
--
-- INSERT INTO public.user_authorities (user_id, authorities)
-- VALUES (1, 0);

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

INSERT INTO public.status (id, name, default_selection)
VALUES (DEFAULT, 'Новая', true);

INSERT INTO public.status (id, name)
VALUES (DEFAULT, 'В работе');

INSERT INTO public.status (id, name)
VALUES (DEFAULT, 'На поддержке');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Критичный');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Высокий');

INSERT INTO public.priority (id, name, default_selection)
VALUES (DEFAULT, 'Средний', true);

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Низкий');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Не трогаем');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Добрый день!', 'дд');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Доброе утро!', 'ду');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Добрый вечер!', 'дв');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Пришлите код от anydesk', 'эни');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Примите подкюлчение', 'подключ');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Сотрудник в пути', 'впути');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Уже решаем', 'решаем');

INSERT INTO public.template (id, text, shortcut)
VALUES (DEFAULT, 'Не наша зона ответственности', 'немы');
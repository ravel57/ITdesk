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

INSERT INTO public.status (id, name)
VALUES (DEFAULT, 'Новая');

INSERT INTO public.status (id, name)
VALUES (DEFAULT, 'В работе');

INSERT INTO public.status (id, name)
VALUES (DEFAULT, 'На поддержке');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Критичный');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Высокий');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Средний');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Низкий');

INSERT INTO public.priority (id, name)
VALUES (DEFAULT, 'Не трогаем');

-- Считать время «Ожидает ответа» только в рабочее время компании. По умолчанию выключено:
-- у существующих установок остаётся календарный отсчёт. Колонка могла быть уже создана Hibernate (ddl-auto: update).
alter table app_settings add column if not exists answer_waiting_working_time_enabled boolean not null default false;

-- Личные настройки интерфейса переезжают из localStorage браузера в БД, чтобы не теряться
-- при смене устройства/браузера.

-- Флаг "прошёл приветственный мастер (/welcome)": существующие пользователи уже видели его раньше
-- (в localStorage), новым он должен показаться — поэтому backfill делаем отдельно от default.
alter table user_t add column if not exists welcome_seen boolean not null default false;
update user_t set welcome_seen = true;

-- Какие поля показывать в карточке заявки и в каком порядке; null — настройки по умолчанию.
alter table user_t add column if not exists task_card_fields_config jsonb;

-- Какие подсказки-туры (онбординги) на отдельных страницах уже пройдены/пропущены: {"ключ-тура": "done"|"skipped"}.
alter table user_t add column if not exists onboarding_progress jsonb;

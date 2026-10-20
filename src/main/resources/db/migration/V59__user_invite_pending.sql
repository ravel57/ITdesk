-- Пользователь, созданный без пароля администратором, ждёт установки пароля по ссылке-приглашению
alter table user_t add column if not exists invite_pending boolean not null default false;

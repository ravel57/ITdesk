-- Двухфакторная аутентификация (TOTP, RFC 6238): секрет приложения-аутентификатора, одноразовые коды восстановления.
alter table user_t add column if not exists mfa_enabled boolean not null default false;
alter table user_t add column if not exists mfa_secret text;
alter table user_t add column if not exists mfa_pending_secret text;
alter table user_t add column if not exists mfa_recovery_codes text;
alter table user_t add column if not exists mfa_last_step bigint;
alter table user_t add column if not exists mfa_enabled_at timestamp(6) with time zone;

-- Обязательна ли 2FA для администраторов: по умолчанию да — при следующем входе администратор настроит приложение.
alter table app_settings add column if not exists mfa_required_for_admins boolean not null default true;

alter table if exists app_settings
    add column if not exists jwt_signing_secret varchar(256);

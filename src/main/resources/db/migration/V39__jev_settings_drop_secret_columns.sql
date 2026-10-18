-- Включение Jev и API-ключ задаются только в конфиге (JEV_ENABLED / JEV_API_KEY)
alter table if exists app_settings
    drop column if exists jev_enabled,
    drop column if exists jev_api_key_encrypted;

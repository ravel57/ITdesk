-- URL, модель и таймаут Jev задаются только в конфиге (JEV_URL / JEV_MODEL / JEV_REQUEST_TIMEOUT_MS)
alter table if exists app_settings
    drop column if exists jev_url,
    drop column if exists jev_model,
    drop column if exists jev_request_timeout_ms;

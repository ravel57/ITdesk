alter table if exists app_settings
    add column if not exists jev_enabled boolean,
    add column if not exists jev_url varchar(512),
    add column if not exists jev_model varchar(64),
    add column if not exists jev_api_key_encrypted text,
    add column if not exists jev_min_confidence double precision,
    add column if not exists jev_reply_required_threshold double precision,
    add column if not exists jev_reply_not_required_threshold double precision,
    add column if not exists jev_priority_excluded text,
    add column if not exists jev_request_timeout_ms integer;

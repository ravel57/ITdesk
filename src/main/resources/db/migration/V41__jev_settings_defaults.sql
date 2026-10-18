-- Пороги и исключённые приоритеты Jev берутся только из настроек приложения
update app_settings set jev_min_confidence = 0.6 where jev_min_confidence is null;
update app_settings set jev_reply_required_threshold = 0.6 where jev_reply_required_threshold is null;
update app_settings set jev_reply_not_required_threshold = 0.25 where jev_reply_not_required_threshold is null;
update app_settings set jev_priority_excluded = 'Приостановленно' where jev_priority_excluded is null;

alter table if exists app_settings
    alter column jev_min_confidence set default 0.6,
    alter column jev_min_confidence set not null,
    alter column jev_reply_required_threshold set default 0.6,
    alter column jev_reply_required_threshold set not null,
    alter column jev_reply_not_required_threshold set default 0.25,
    alter column jev_reply_not_required_threshold set not null,
    alter column jev_priority_excluded set default 'Приостановленно',
    alter column jev_priority_excluded set not null;

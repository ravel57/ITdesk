-- Режим детектора: LOG_ONLY — только лог, CREATE_DRAFT — создавать черновик инцидента и привязывать заявки
alter table if exists app_settings
    add column if not exists jev_incident_mode varchar(16) not null default 'LOG_ONLY',
    add column if not exists jev_incident_p1_min_clients integer not null default 10;

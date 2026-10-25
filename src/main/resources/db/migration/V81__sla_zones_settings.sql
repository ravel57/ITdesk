-- Границы зон SLA (оранжевая и красная) в процентах оставшегося времени; общие для всех SLA
alter table app_settings add column if not exists sla_orange_start_percent integer not null default 50;
alter table app_settings add column if not exists sla_red_start_percent integer not null default 25;

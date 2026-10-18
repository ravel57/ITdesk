-- Журнал решений Jev: уверенность по каждому вопросу каждой автоматизации (для настройки порогов)
create table if not exists jev_decision (
    id           bigserial primary key,
    created_at   timestamptz      not null,
    rule_id      bigint,
    rule_name    varchar(255),
    action       varchar(64),
    context      varchar(255),
    question     varchar(64)      not null,
    choice       varchar(255),
    confidence   double precision not null,
    threshold    double precision,
    outcome      varchar(32)      not null
);

create index if not exists idx_jev_decision_created on jev_decision (created_at desc);
create index if not exists idx_jev_decision_action on jev_decision (action, question, created_at desc);

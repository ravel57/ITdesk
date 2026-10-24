alter table user_t add column if not exists notify_portal_messages boolean not null default false;
update user_t set notify_portal_messages = false where notify_portal_messages is null;
alter table user_t alter column notify_portal_messages set default false;
alter table user_t alter column notify_portal_messages set not null;

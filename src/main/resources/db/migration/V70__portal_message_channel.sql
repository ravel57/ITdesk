-- MessageFrom is stored as an ordinal: keep TELEGRAM=0, WHATSAPP=1, EMAIL=2;
-- append PORTAL=3 without renumbering the existing channels.
alter table client drop constraint if exists client_message_from_check;
alter table client add constraint client_message_from_check check (message_from between 0 and 3);

-- Only identify portal cards with no previously configured channel.
update client c set message_from = 3
where c.message_from is null
  and exists (
    select 1 from user_t u
    join user_authorities a on a.user_id = u.id and a.authorities = 'CLIENT'
    where u.client_id = c.id
  );

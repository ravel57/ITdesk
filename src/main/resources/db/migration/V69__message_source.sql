-- Keep the source on each message: the client's channel does not identify portal messages
-- reliably when the same client also uses email or a messenger.
alter table message add column if not exists from_source varchar(32);

-- Sources of historical messages cannot be reconstructed reliably from the client's
-- current channel. Leave them null instead of assigning a potentially wrong source.

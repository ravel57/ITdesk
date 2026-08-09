alter table public.user_authorities
    drop constraint user_authorities_authorities_check;

alter table public.user_authorities
    add constraint user_authorities_authorities_check
        check ((authorities)::text = ANY
               ((ARRAY ['ADMIN'::character varying, 'OPERATOR'::character varying, 'CLIENT'::character varying])::text[]));
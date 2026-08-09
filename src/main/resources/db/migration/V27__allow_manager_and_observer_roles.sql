ALTER TABLE user_authorities
    DROP CONSTRAINT IF EXISTS user_authorities_authorities_check;

ALTER TABLE user_authorities
    ADD CONSTRAINT user_authorities_authorities_check
    CHECK (authorities IN (
        'ADMIN',
        'MANAGER',
        'OPERATOR',
        'OBSERVER',
        'CLIENT'
    ));

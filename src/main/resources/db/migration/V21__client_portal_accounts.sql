-- Привязка локальной/SSO/LDAP учётной записи к карточке клиента.
-- PostgreSQL. При необходимости измените номер версии под текущую цепочку Flyway.
ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS client_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_user_t_client_id
    ON user_t (client_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_user_t_client'
    ) THEN
        ALTER TABLE user_t
            ADD CONSTRAINT fk_user_t_client
            FOREIGN KEY (client_id)
            REFERENCES client (id)
            ON DELETE SET NULL;
    END IF;
END
$$;

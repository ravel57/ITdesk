ALTER TABLE jwt_token
    ADD COLUMN IF NOT EXISTS name VARCHAR(120),
    ADD COLUMN IF NOT EXISTS scopes VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS signing_key_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS last_used_ip VARCHAR(64),
    ADD COLUMN IF NOT EXISTS last_user_agent VARCHAR(512),
    ADD COLUMN IF NOT EXISTS last_endpoint VARCHAR(512),
    ADD COLUMN IF NOT EXISTS use_count BIGINT NOT NULL DEFAULT 0;

UPDATE jwt_token
SET name = COALESCE(NULLIF(BTRIM(name), ''), 'JWT #' || id)
WHERE name IS NULL OR BTRIM(name) = '';

UPDATE jwt_token
SET scopes = 'api:full'
WHERE scopes IS NULL OR BTRIM(scopes) = '';

ALTER TABLE jwt_token
    ALTER COLUMN name SET NOT NULL,
    ALTER COLUMN scopes SET NOT NULL;

CREATE TABLE IF NOT EXISTS jwt_signing_key (
    key_id VARCHAR(64) PRIMARY KEY,
    secret TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    retired_at TIMESTAMPTZ NULL,
    active BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_jwt_signing_key_active
    ON jwt_signing_key (active)
    WHERE active = TRUE;

CREATE INDEX IF NOT EXISTS idx_jwt_token_last_used
    ON jwt_token (last_used_at DESC);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_jwt_token_signing_key'
    ) THEN
        ALTER TABLE jwt_token
            ADD CONSTRAINT fk_jwt_token_signing_key
            FOREIGN KEY (signing_key_id)
            REFERENCES jwt_signing_key (key_id)
            ON DELETE RESTRICT;
    END IF;
END $$;

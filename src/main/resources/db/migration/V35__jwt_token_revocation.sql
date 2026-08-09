CREATE TABLE IF NOT EXISTS jwt_token (
    id BIGSERIAL PRIMARY KEY,
    jti VARCHAR(64) NOT NULL UNIQUE,
    user_id BIGINT NOT NULL,
    username VARCHAR(255) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NULL,
    revoked_by_user_id BIGINT NULL,
    CONSTRAINT fk_jwt_token_user
        FOREIGN KEY (user_id) REFERENCES user_t (id) ON DELETE CASCADE,
    CONSTRAINT fk_jwt_token_revoked_by
        FOREIGN KEY (revoked_by_user_id) REFERENCES user_t (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_jwt_token_user_issued
    ON jwt_token (user_id, issued_at DESC);

CREATE INDEX IF NOT EXISTS idx_jwt_token_active
    ON jwt_token (revoked_at, expires_at);

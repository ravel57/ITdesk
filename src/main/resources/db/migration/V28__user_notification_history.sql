CREATE TABLE IF NOT EXISTS user_notification_history (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event VARCHAR(64) NOT NULL,
    title VARCHAR(160) NOT NULL,
    body TEXT NOT NULL,
    source_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    read_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_user_notification_history_user
        FOREIGN KEY (user_id) REFERENCES user_t (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_notification_history_user_created
    ON user_notification_history (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_notification_history_user_unread
    ON user_notification_history (user_id, created_at DESC)
    WHERE read_at IS NULL;

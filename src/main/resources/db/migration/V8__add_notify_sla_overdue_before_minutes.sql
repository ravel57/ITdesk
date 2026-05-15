ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_chat_unanswered_too_long_minutes INTEGER NOT NULL DEFAULT 30;

ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_deadline_overdue_before_minutes INTEGER NOT NULL DEFAULT 30;

ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_deadline_overdue_before_minutes_enabled BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_deadline_overdue BOOLEAN NOT NULL DEFAULT false;
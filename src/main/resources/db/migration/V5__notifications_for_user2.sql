ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_sla_half_time_passed BOOLEAN;

ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_sla_overdue BOOLEAN;

ALTER TABLE user_t
    ADD COLUMN IF NOT EXISTS notify_chat_unanswered_too_long BOOLEAN;

UPDATE user_t
SET notify_sla_half_time_passed = FALSE
WHERE notify_sla_half_time_passed IS NULL;

UPDATE user_t
SET notify_sla_overdue = FALSE
WHERE notify_sla_overdue IS NULL;

UPDATE user_t
SET notify_chat_unanswered_too_long = FALSE
WHERE notify_chat_unanswered_too_long IS NULL;

ALTER TABLE user_t
    ALTER COLUMN notify_sla_half_time_passed SET DEFAULT FALSE,
    ALTER COLUMN notify_sla_half_time_passed SET NOT NULL;

ALTER TABLE user_t
    ALTER COLUMN notify_sla_overdue SET DEFAULT FALSE,
    ALTER COLUMN notify_sla_overdue SET NOT NULL;

ALTER TABLE user_t
    ALTER COLUMN notify_chat_unanswered_too_long SET DEFAULT FALSE,
    ALTER COLUMN notify_chat_unanswered_too_long SET NOT NULL;
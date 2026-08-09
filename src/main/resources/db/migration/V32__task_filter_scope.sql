-- Existing saved filters were team-wide before scopes were introduced,
-- so legacy rows are migrated as GLOBAL.
ALTER TABLE task_filter
    ADD COLUMN IF NOT EXISTS scope VARCHAR(32) NOT NULL DEFAULT 'GLOBAL';

ALTER TABLE task_filter
    ADD COLUMN IF NOT EXISTS owner_user_id BIGINT;

UPDATE task_filter
SET scope = 'GLOBAL'
WHERE scope IS NULL;

CREATE INDEX IF NOT EXISTS idx_task_filter_scope_owner
    ON task_filter (scope, owner_user_id);

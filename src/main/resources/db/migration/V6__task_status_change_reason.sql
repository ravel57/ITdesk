ALTER TABLE task
    ADD COLUMN IF NOT EXISTS status_change_reason TEXT;

UPDATE event
SET trigger_type = 'TASK_CLOSED'
WHERE trigger_type = 'TASK_COMPLETED';
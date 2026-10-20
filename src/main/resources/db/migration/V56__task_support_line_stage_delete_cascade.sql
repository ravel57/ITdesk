-- V20 leaves existing tables untouched. Replace task foreign keys created by
-- Hibernate as well as the original migration, regardless of their names.
DO $$
DECLARE
    task_fk record;
BEGIN
    FOR task_fk IN
        SELECT c.conname
        FROM pg_constraint c
        JOIN pg_attribute a ON a.attrelid = c.conrelid
                           AND c.conkey = ARRAY[a.attnum]
        WHERE c.contype = 'f'
          AND c.conrelid = 'task_support_line_stage'::regclass
          AND c.confrelid = 'task'::regclass
          AND a.attname = 'task_id'
    LOOP
        EXECUTE format('ALTER TABLE task_support_line_stage DROP CONSTRAINT %I', task_fk.conname);
    END LOOP;

    ALTER TABLE task_support_line_stage
        ADD CONSTRAINT fk_task_support_line_stage_task
        FOREIGN KEY (task_id) REFERENCES task(id) ON DELETE CASCADE;
END
$$;

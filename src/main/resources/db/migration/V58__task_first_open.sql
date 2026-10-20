-- First opening is personal and survives executor changes and subsequent messages.
-- Existing tasks start unopened: historical opening information was not recorded.
CREATE TABLE IF NOT EXISTS task_user_open (
    task_id bigint NOT NULL REFERENCES task(id) ON DELETE CASCADE,
    user_id bigint NOT NULL REFERENCES user_t(id) ON DELETE CASCADE,
    opened_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (task_id, user_id)
);

-- Hibernate's schema update runs before Flyway in this application and can
-- already have created the entity table, without database foreign keys/defaults.
ALTER TABLE task_user_open ALTER COLUMN opened_at SET DEFAULT now();

DO $$
DECLARE
    relation record;
    existing_fk record;
BEGIN
    FOR relation IN
        SELECT * FROM (VALUES
            ('task_id', 'task', 'fk_task_user_open_task'),
            ('user_id', 'user_t', 'fk_task_user_open_user')
        ) AS relations(column_name, parent_table, constraint_name)
    LOOP
        FOR existing_fk IN
            SELECT c.conname
            FROM pg_constraint c
            JOIN pg_attribute a ON a.attrelid = c.conrelid
                               AND c.conkey = ARRAY[a.attnum]
            WHERE c.contype = 'f'
              AND c.conrelid = 'task_user_open'::regclass
              AND c.confrelid = relation.parent_table::regclass
              AND a.attname = relation.column_name
        LOOP
            EXECUTE format('ALTER TABLE task_user_open DROP CONSTRAINT %I', existing_fk.conname);
        END LOOP;
        EXECUTE format(
            'ALTER TABLE task_user_open ADD CONSTRAINT %I FOREIGN KEY (%I) REFERENCES %I(id) ON DELETE CASCADE',
            relation.constraint_name, relation.column_name, relation.parent_table
        );
    END LOOP;
END
$$;

CREATE INDEX IF NOT EXISTS idx_task_user_open_user ON task_user_open(user_id, task_id);
CREATE INDEX IF NOT EXISTS idx_task_executor_active
    ON task(executor_id) WHERE coalesce(completed, false) = false AND coalesce(frozen, false) = false;

ALTER TABLE task_type
    ADD COLUMN IF NOT EXISTS order_number INTEGER;

WITH ordered AS (SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
                 FROM task_type)
UPDATE task_type tt
SET order_number = ordered.rn
FROM ordered
WHERE tt.id = ordered.id
  AND tt.order_number IS NULL;

ALTER TABLE task_type
    ALTER COLUMN order_number SET DEFAULT 0;

ALTER TABLE task_type
    ALTER COLUMN order_number SET NOT NULL;

ALTER TABLE task_type
    ADD COLUMN IF NOT EXISTS default_selection BOOLEAN;

UPDATE task_type
SET default_selection = false
WHERE default_selection IS NULL;

WITH preferred AS (SELECT id
                   FROM task_type
                   ORDER BY CASE WHEN type = 'Запрос' THEN 0 ELSE 1 END, id
                   LIMIT 1)
UPDATE task_type
SET default_selection = id = (SELECT id FROM preferred);

ALTER TABLE task_type
    ALTER COLUMN default_selection SET DEFAULT false;

ALTER TABLE task_type
    ALTER COLUMN default_selection SET NOT NULL;
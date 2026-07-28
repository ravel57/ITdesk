DO $$
DECLARE
    allowed_values text := $values$
        'MANUAL_MACRO_APPLIED',
        'MESSAGE_INCOMING',
        'MESSAGE_OUTGOING',
        'MESSAGE_EDITED',
        'MESSAGE_MENTIONED_USER',
        'MESSAGE_CONTAINS_KEYWORD',
        'MESSAGE_DELETED',
        'ATTACHMENT_ADDED',
        'SLA_WARNING',
        'SLA_BREACHED',
        'SLA_STARTED',
        'SLA_PAUSED',
        'SLA_RESUMED',
        'SLA_CANCELLED',
        'SLA_COMPLETED',
        'INACTIVITY_TIMEOUT',
        'SCHEDULED_CRON',
        'TASK_CREATED',
        'TASK_UPDATED',
        'TASK_STATUS_CHANGED',
        'TASK_PRIORITY_CHANGED',
        'TASK_TYPE_CHANGED',
        'TASK_ASSIGNEE_CHANGED',
        'TASK_GROUP_CHANGED',
        'TASK_TAG_ADDED',
        'TASK_TAG_REMOVED',
        'TASK_DUE_DATE_CHANGED',
        'TASK_CLOSED',
        'TASK_REOPENED',
        'TASK_OVERDUE',
        'TASK_MESSAGE_MENTIONED_USER',
        'TASK_COMMENT_ADDED',
        'TASK_COMMENT_DELETED',
        'TASK_EXECUTOR_CHANGED',
        'CLIENT_CREATED',
        'CLIENT_UPDATED',
        'CLIENT_DELETED',
        'USER_CREATED',
        'USER_UPDATED',
        'USER_OPEN_DIALOG',
        'USER_CLOSED_DIALOG',
        'KNOWLEDGE_BASE_ARTICLE_CREATED',
        'KNOWLEDGE_BASE_ARTICLE_UPDATED',
        'KNOWLEDGE_BASE_ARTICLE_DELETED',
        'KNOWLEDGE_BASE_ARTICLE_PUBLISHED',
        'WEBHOOK_RECEIVED',
        'INTEGRATION_EVENT_RECEIVED',
        'WORKING_HOURS_STARTED',
        'WORKING_HOURS_ENDED',
        'AUTOMATION_RULE_CREATED',
        'AUTOMATION_RULE_UPDATED',
        'AUTOMATION_RULE_DISABLED',
        'AUTOMATION_RULE_FAILED',
        'SYSTEM_MAINTENANCE_STARTED',
        'SYSTEM_MAINTENANCE_ENDED'
    $values$;
    target record;
BEGIN
    FOR target IN
        SELECT *
        FROM (VALUES
            ('automation_trigger', 'automation_trigger_trigger_type_check', 'trigger_type'),
            ('event', 'event_trigger_type_check', 'trigger_type'),
            ('automation_workflow_run', 'automation_workflow_run_trigger_type_check', 'trigger_type'),
            ('automation_workflow_run', 'automation_workflow_run_wait_event_type_check', 'wait_event_type')
        ) AS constraints_to_update(table_name, constraint_name, column_name)
    LOOP
        IF to_regclass(format('%I.%I', current_schema(), target.table_name)) IS NULL THEN
            CONTINUE;
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = target.table_name
              AND column_name = target.column_name
        ) THEN
            CONTINUE;
        END IF;

        EXECUTE format(
            'ALTER TABLE %I DROP CONSTRAINT IF EXISTS %I',
            target.table_name,
            target.constraint_name
        );

        EXECUTE format(
            'ALTER TABLE %I ADD CONSTRAINT %I CHECK (%I IN (%s))',
            target.table_name,
            target.constraint_name,
            target.column_name,
            allowed_values
        );
    END LOOP;
END
$$;

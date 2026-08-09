-- Update PostgreSQL enum-like CHECK constraint for event.trigger_type
-- Required after adding COMMUNICATION_SEND_REQUESTED to TriggerType.

BEGIN;

ALTER TABLE event
    DROP CONSTRAINT IF EXISTS event_trigger_type_check;

ALTER TABLE event
    ADD CONSTRAINT event_trigger_type_check
    CHECK (trigger_type IN (
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
        'FIRST_RESPONSE_SLA_WARNING',
        'FIRST_RESPONSE_SLA_BREACHED',
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
        'TASK_SERVICE_CHANGED',
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
        'COMMUNICATION_SEND_REQUESTED',
        'WORKING_HOURS_STARTED',
        'WORKING_HOURS_ENDED',
        'AUTOMATION_RULE_CREATED',
        'AUTOMATION_RULE_UPDATED',
        'AUTOMATION_RULE_DISABLED',
        'AUTOMATION_RULE_FAILED',
        'SYSTEM_MAINTENANCE_STARTED',
        'SYSTEM_MAINTENANCE_ENDED'
    ));

COMMIT;

-- Optional verification:
-- SELECT pg_get_constraintdef(oid)
-- FROM pg_constraint
-- WHERE conname = 'event_trigger_type_check';

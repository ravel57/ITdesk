-- Hibernate (ddl-auto: update) мог создать CHECK для прежнего набора Outcome.
-- CONNECTION_LOST и CONNECTION_RESTORED появились позже, поэтому запись потери связи отклоняется.
-- Допустимые значения проверяет enum MonitoringWebhookLog.Outcome в приложении.
alter table if exists monitoring_webhook_log
    drop constraint if exists monitoring_webhook_log_outcome_check;

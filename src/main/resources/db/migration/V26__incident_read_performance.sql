-- Speeds up the incident list, task banner and incident detail queries.
CREATE INDEX IF NOT EXISTS idx_support_incident_status_updated
    ON support_incident (status, updated_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_support_incident_next_update
    ON support_incident (next_update_at)
    WHERE next_update_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_incident_task_link_incident_active
    ON support_incident_task_link (incident_id, active, linked_at, id);

CREATE INDEX IF NOT EXISTS idx_incident_task_link_task_active
    ON support_incident_task_link (task_id, active);

CREATE INDEX IF NOT EXISTS idx_incident_timeline_incident_created
    ON support_incident_timeline_entry (incident_id, created_at, id);

CREATE INDEX IF NOT EXISTS idx_support_incident_services_incident
    ON support_incident_services (incident_id, service_id);

CREATE INDEX IF NOT EXISTS idx_support_incident_organizations_incident
    ON support_incident_organizations (incident_id, organization_id);

CREATE INDEX IF NOT EXISTS idx_support_incident_participants_incident
    ON support_incident_participants (incident_id, user_id);

CREATE INDEX IF NOT EXISTS idx_support_incident_observers_incident
    ON support_incident_observers (incident_id, user_id);

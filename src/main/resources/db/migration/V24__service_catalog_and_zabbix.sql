-- ULDesk service catalog, organization contracts and Zabbix monitoring.
-- PostgreSQL / Flyway migration. The statements are intentionally additive so
-- the migration can be applied to installations that already have the legacy
-- service(id, name, description, monitoring_url) table.

CREATE TABLE IF NOT EXISTS service_criticality (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    default_selection BOOLEAN,
    order_number INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS zabbix_integration (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    base_url VARCHAR(1024) NOT NULL,
    username VARCHAR(255),
    password VARCHAR(2048),
    api_token VARCHAR(4096),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    request_timeout_seconds INTEGER NOT NULL DEFAULT 15,
    last_tested_at TIMESTAMPTZ,
    last_test_success BOOLEAN,
    last_test_message VARCHAR(2048),
    CONSTRAINT uk_zabbix_integration_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS service (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    description TEXT,
    monitoring_url VARCHAR(1024)
);

ALTER TABLE service ADD COLUMN IF NOT EXISTS code VARCHAR(80);
ALTER TABLE service ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE service ADD COLUMN IF NOT EXISTS order_number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE service ADD COLUMN IF NOT EXISTS criticality_id BIGINT;
ALTER TABLE service ADD COLUMN IF NOT EXISTS owner_id BIGINT;
ALTER TABLE service ADD COLUMN IF NOT EXISTS default_support_line_id BIGINT;
ALTER TABLE service ADD COLUMN IF NOT EXISTS default_priority_id BIGINT;
ALTER TABLE service ADD COLUMN IF NOT EXISTS first_response_sla_minutes INTEGER;
ALTER TABLE service ADD COLUMN IF NOT EXISTS resolution_sla_minutes INTEGER;
ALTER TABLE service ADD COLUMN IF NOT EXISTS support_calendar VARCHAR(80) DEFAULT 'GENERAL_SETTINGS';
ALTER TABLE service ADD COLUMN IF NOT EXISTS pause_sla_on_waiting_client BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE service ADD COLUMN IF NOT EXISTS auto_assign_default_line BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE service ADD COLUMN IF NOT EXISTS priority_rules JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE service ADD COLUMN IF NOT EXISTS ola_stages JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE service ADD COLUMN IF NOT EXISTS zabbix_integration_id BIGINT;
ALTER TABLE service ADD COLUMN IF NOT EXISTS zabbix_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE service ADD COLUMN IF NOT EXISTS monitoring_identifiers JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE service ADD COLUMN IF NOT EXISTS monitoring_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CONFIGURED';
ALTER TABLE service ADD COLUMN IF NOT EXISTS last_monitoring_at TIMESTAMPTZ;
ALTER TABLE service ADD COLUMN IF NOT EXISTS last_monitoring_message VARCHAR(2048);
ALTER TABLE service ADD COLUMN IF NOT EXISTS last_monitoring_data JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE service ADD COLUMN IF NOT EXISTS monitoring_url VARCHAR(1024);
ALTER TABLE service ALTER COLUMN monitoring_url TYPE VARCHAR(1024);
ALTER TABLE service ALTER COLUMN description TYPE TEXT;

UPDATE service
SET code = 'SVC-' || id
WHERE code IS NULL OR btrim(code) = '';

UPDATE service
SET name = 'Сервис #' || id
WHERE name IS NULL OR btrim(name) = '';

UPDATE service SET support_calendar = 'GENERAL_SETTINGS'
WHERE support_calendar IS NULL OR btrim(support_calendar) = '';
UPDATE service SET priority_rules = '[]'::jsonb WHERE priority_rules IS NULL;
UPDATE service SET ola_stages = '[]'::jsonb WHERE ola_stages IS NULL;
UPDATE service SET monitoring_identifiers = '{}'::jsonb WHERE monitoring_identifiers IS NULL;
UPDATE service SET last_monitoring_data = '{}'::jsonb WHERE last_monitoring_data IS NULL;
UPDATE service SET monitoring_status = 'NOT_CONFIGURED' WHERE monitoring_status IS NULL;

ALTER TABLE service ALTER COLUMN code SET NOT NULL;
ALTER TABLE service ALTER COLUMN name SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uk_service_code ON service (code);
CREATE INDEX IF NOT EXISTS idx_service_active_order ON service (active, order_number, name);
CREATE INDEX IF NOT EXISTS idx_service_monitoring_status ON service (monitoring_status);
CREATE INDEX IF NOT EXISTS idx_service_zabbix_integration ON service (zabbix_integration_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_service_criticality') THEN
        ALTER TABLE service ADD CONSTRAINT fk_service_criticality
            FOREIGN KEY (criticality_id) REFERENCES service_criticality(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_service_owner') THEN
        ALTER TABLE service ADD CONSTRAINT fk_service_owner
            FOREIGN KEY (owner_id) REFERENCES user_t(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_service_default_support_line') THEN
        ALTER TABLE service ADD CONSTRAINT fk_service_default_support_line
            FOREIGN KEY (default_support_line_id) REFERENCES support_line(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_service_default_priority') THEN
        ALTER TABLE service ADD CONSTRAINT fk_service_default_priority
            FOREIGN KEY (default_priority_id) REFERENCES priority(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_service_zabbix_integration') THEN
        ALTER TABLE service ADD CONSTRAINT fk_service_zabbix_integration
            FOREIGN KEY (zabbix_integration_id) REFERENCES zabbix_integration(id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS service_task_types (
    service_id BIGINT NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    task_type_id BIGINT NOT NULL REFERENCES task_type(id),
    PRIMARY KEY (service_id, task_type_id)
);

CREATE TABLE IF NOT EXISTS service_executors (
    service_id BIGINT NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL REFERENCES user_t(id),
    PRIMARY KEY (service_id, user_id)
);

CREATE TABLE IF NOT EXISTS service_dependencies (
    service_id BIGINT NOT NULL REFERENCES service(id) ON DELETE CASCADE,
    depends_on_service_id BIGINT NOT NULL REFERENCES service(id),
    PRIMARY KEY (service_id, depends_on_service_id),
    CONSTRAINT chk_service_dependency_not_self CHECK (service_id <> depends_on_service_id)
);
CREATE INDEX IF NOT EXISTS idx_service_dependencies_target ON service_dependencies (depends_on_service_id);

CREATE TABLE IF NOT EXISTS organization_service_assignment (
    id BIGSERIAL PRIMARY KEY,
    organization_id BIGINT NOT NULL REFERENCES organization(id),
    service_id BIGINT NOT NULL REFERENCES service(id),
    contract_number VARCHAR(160),
    tariff_name VARCHAR(255),
    valid_from DATE,
    valid_to DATE,
    support_calendar VARCHAR(80) DEFAULT 'GENERAL_SETTINGS',
    sla_policy_name VARCHAR(255),
    first_response_sla_minutes INTEGER,
    resolution_sla_minutes INTEGER,
    available_channels JSONB NOT NULL DEFAULT '[]'::jsonb,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    default_for_organization BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    CONSTRAINT chk_org_service_assignment_dates CHECK (valid_to IS NULL OR valid_from IS NULL OR valid_to >= valid_from),
    CONSTRAINT chk_org_service_first_response_sla CHECK (first_response_sla_minutes IS NULL OR first_response_sla_minutes >= 0),
    CONSTRAINT chk_org_service_resolution_sla CHECK (resolution_sla_minutes IS NULL OR resolution_sla_minutes >= 0)
);
CREATE INDEX IF NOT EXISTS idx_org_service_assignment_org ON organization_service_assignment (organization_id);
CREATE INDEX IF NOT EXISTS idx_org_service_assignment_service ON organization_service_assignment (service_id);
CREATE INDEX IF NOT EXISTS idx_org_service_assignment_effective ON organization_service_assignment (organization_id, active, valid_from, valid_to);
CREATE UNIQUE INDEX IF NOT EXISTS uk_org_service_default_active
    ON organization_service_assignment (organization_id)
    WHERE default_for_organization = TRUE AND active = TRUE;

ALTER TABLE task ADD COLUMN IF NOT EXISTS service_id BIGINT;
ALTER TABLE task ADD COLUMN IF NOT EXISTS first_response_sla_minutes INTEGER;
ALTER TABLE task ADD COLUMN IF NOT EXISTS first_response_deadline TIMESTAMPTZ;
ALTER TABLE task ADD COLUMN IF NOT EXISTS first_response_at TIMESTAMPTZ;
ALTER TABLE task ADD COLUMN IF NOT EXISTS ola_warning_percent INTEGER;
CREATE INDEX IF NOT EXISTS idx_task_service ON task (service_id);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_task_service') THEN
        ALTER TABLE task ADD CONSTRAINT fk_task_service
            FOREIGN KEY (service_id) REFERENCES service(id);
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS support_incident_services (
    incident_id BIGINT NOT NULL REFERENCES support_incident(id) ON DELETE CASCADE,
    service_id BIGINT NOT NULL REFERENCES service(id),
    PRIMARY KEY (incident_id, service_id)
);
CREATE INDEX IF NOT EXISTS idx_support_incident_services_service ON support_incident_services (service_id);

-- Migrate the legacy free-text incident service name when it uniquely matches
-- a catalog service. Ambiguous names are intentionally left as legacy text.
INSERT INTO support_incident_services (incident_id, service_id)
SELECT incident.id, min(service.id)
FROM support_incident incident
JOIN service ON lower(btrim(service.name)) = lower(btrim(incident.service_name))
WHERE incident.service_name IS NOT NULL AND btrim(incident.service_name) <> ''
GROUP BY incident.id
HAVING count(service.id) = 1
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS plugin_grant (
    id BIGSERIAL PRIMARY KEY,
    plugin_key VARCHAR(128) NOT NULL,
    permission VARCHAR(1024) NOT NULL,
    approved BOOLEAN NOT NULL DEFAULT FALSE,
    requested_at TIMESTAMPTZ NOT NULL,
    approved_at TIMESTAMPTZ NULL,
    approved_by VARCHAR(128) NULL,
    CONSTRAINT uq_plugin_grant UNIQUE (plugin_key, permission)
);

CREATE INDEX IF NOT EXISTS idx_plugin_grant_plugin_key
    ON plugin_grant (plugin_key);

CREATE TABLE IF NOT EXISTS plugin_secret (
    id BIGSERIAL PRIMARY KEY,
    plugin_key VARCHAR(128) NOT NULL,
    name VARCHAR(64) NOT NULL,
    value_encrypted TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by VARCHAR(128) NULL,
    CONSTRAINT uq_plugin_secret UNIQUE (plugin_key, name)
);

CREATE INDEX IF NOT EXISTS idx_plugin_secret_plugin_key
    ON plugin_secret (plugin_key);

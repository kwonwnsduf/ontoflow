ALTER TABLE connectors DROP CONSTRAINT IF EXISTS connectors_system_type_check;
ALTER TABLE connectors DROP CONSTRAINT IF EXISTS connectors_tenant_id_system_type_key;
ALTER TABLE connectors ADD CONSTRAINT connectors_system_type_check CHECK (system_type IN ('ALPHA','BETA','POSTGRESQL','MYSQL','GITHUB','SLACK'));
ALTER TABLE connectors ADD COLUMN resource_key VARCHAR(120);
ALTER TABLE connectors ADD COLUMN last_synced_at TIMESTAMPTZ;
ALTER TABLE connectors ADD COLUMN sync_lease_id UUID;
ALTER TABLE connectors ADD COLUMN sync_lock_until TIMESTAMPTZ;
CREATE UNIQUE INDEX connector_tenant_resource_idx ON connectors(tenant_id,system_type,resource_key);
CREATE TABLE connector_registry (
    system_type VARCHAR(20) PRIMARY KEY,
    auth_type VARCHAR(30) NOT NULL,
    resource_type VARCHAR(30) NOT NULL,
    capability VARCHAR(80) NOT NULL,
    input_schema TEXT NOT NULL,
    output_schema TEXT NOT NULL
);
CREATE TABLE connector_credentials (
    id UUID PRIMARY KEY,
    connector_id UUID NOT NULL UNIQUE REFERENCES connectors(id) ON DELETE CASCADE,
    ciphertext TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    rotated_at TIMESTAMPTZ
);
INSERT INTO connector_registry VALUES
 ('POSTGRESQL','DB_PASSWORD','VIEW','source.read','{"resourceKey":"string","cursor":"integer"}','{"items":"array","nextCursor":"string"}'),
 ('MYSQL','DB_PASSWORD','VIEW','source.read','{"resourceKey":"string","cursor":"integer"}','{"items":"array","nextCursor":"string"}'),
 ('GITHUB','GITHUB_APP','REPOSITORY','source.read','{"resourceKey":"string","cursor":"integer"}','{"items":"array","nextCursor":"string"}'),
 ('SLACK','OAUTH_BOT','CHANNEL','source.read','{"resourceKey":"string","cursor":"string"}','{"items":"array","nextCursor":"string"}');
CREATE TABLE source_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    connector_id UUID NOT NULL REFERENCES connectors(id) ON DELETE CASCADE,
    resource_key VARCHAR(120) NOT NULL,
    source_id VARCHAR(300) NOT NULL,
    source_url VARCHAR(1000),
    source_updated_at TIMESTAMPTZ,
    collected_at TIMESTAMPTZ NOT NULL,
    permission_scope VARCHAR(300) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    payload TEXT,
    UNIQUE (connector_id, resource_key, source_id)
);
CREATE INDEX source_records_tenant_idx ON source_records(tenant_id,connector_id);
CREATE TABLE source_sync_state (
    connector_id UUID NOT NULL REFERENCES connectors(id) ON DELETE CASCADE,
    resource_key VARCHAR(120) NOT NULL,
    cursor_value VARCHAR(500),
    scan_started_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_error VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (connector_id, resource_key)
);
CREATE TABLE source_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    connector_id UUID NOT NULL REFERENCES connectors(id) ON DELETE CASCADE,
    resource_key VARCHAR(120) NOT NULL,
    source_id VARCHAR(300) NOT NULL,
    event_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX source_events_tenant_idx ON source_events(tenant_id,connector_id,occurred_at);
CREATE TABLE source_oauth_states (
    state_hash VARCHAR(64) PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    actor_id UUID NOT NULL REFERENCES users(id),
    connector_name VARCHAR(100) NOT NULL,
    resource_key VARCHAR(120) NOT NULL,
    installation_id VARCHAR(30),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE connectors (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    system_type VARCHAR(20) NOT NULL CHECK (system_type IN ('ALPHA','BETA')),
    name VARCHAR(100) NOT NULL,
    credential_ciphertext TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    last_error VARCHAR(200),
    last_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, system_type)
);
CREATE INDEX connector_tenant_idx ON connectors(tenant_id);
CREATE TABLE connector_capabilities (
    connector_id UUID NOT NULL REFERENCES connectors(id) ON DELETE CASCADE,
    action VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (connector_id, action)
);

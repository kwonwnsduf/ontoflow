CREATE TABLE users (
    id UUID PRIMARY KEY, email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL, display_name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL, created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE tenants (
    id UUID PRIMARY KEY, name VARCHAR(100) NOT NULL, slug VARCHAR(80) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE tenant_members (
    id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
    user_id UUID NOT NULL REFERENCES users(id), role VARCHAR(20) NOT NULL
        CHECK (role IN ('OWNER','ADMIN','OPERATOR','VIEWER')),
    created_at TIMESTAMPTZ NOT NULL, UNIQUE (tenant_id, user_id)
);
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES users(id),
    family_id UUID NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL, consumed BOOLEAN NOT NULL, revoked BOOLEAN NOT NULL
);
CREATE INDEX refresh_family_idx ON refresh_tokens(family_id);
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY, tenant_id UUID REFERENCES tenants(id), user_id UUID NOT NULL REFERENCES users(id),
    action VARCHAR(80) NOT NULL, target_id UUID, trace_id VARCHAR(64), created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX audit_tenant_idx ON audit_logs(tenant_id, created_at);

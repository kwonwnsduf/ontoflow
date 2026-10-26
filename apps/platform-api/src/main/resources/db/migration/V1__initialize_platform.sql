CREATE TABLE platform_metadata (
    id UUID PRIMARY KEY,
    schema_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


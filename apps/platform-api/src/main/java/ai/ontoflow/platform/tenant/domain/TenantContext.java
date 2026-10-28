package ai.ontoflow.platform.tenant.domain;

import java.util.UUID;

public record TenantContext(UUID tenantId, UUID userId, Role role) {}

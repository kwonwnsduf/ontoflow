package ai.ontoflow.platform.audit.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditResponse(
        UUID id,
        UUID tenantId,
        UUID userId,
        String action,
        UUID targetId,
        String traceId,
        Instant createdAt) {}

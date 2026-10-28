package ai.ontoflow.platform.audit.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "Audit")
@Table(name = "audit_logs")
public class AuditLog {
    @Id private UUID id;

    private UUID tenantId;

    private UUID userId;

    private String action;

    private UUID targetId;

    private String traceId;

    private Instant createdAt;

    protected AuditLog() {}

    public AuditLog(
            UUID id,
            UUID tenantId,
            UUID userId,
            String action,
            UUID targetId,
            String traceId,
            Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.action = action;
        this.targetId = targetId;
        this.traceId = traceId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getAction() {
        return action;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public String getTraceId() {
        return traceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

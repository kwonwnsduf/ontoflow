package ai.ontoflow.platform.tenant.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "Member")
@Table(
        name = "tenant_members",
        uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "user_id"}))
public class TenantMember {
    @Id private UUID id;

    private UUID tenantId;

    private UUID userId;

    @Enumerated(EnumType.STRING)
    private Role role;

    private Instant createdAt;

    protected TenantMember() {}

    public TenantMember(UUID id, UUID tenantId, UUID userId, Role role, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.role = role;
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

    public Role getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void changeRole(Role role) {
        this.role = role;
    }
}

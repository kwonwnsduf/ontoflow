package ai.ontoflow.platform.tenant.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "Tenant")
@Table(name = "tenants")
public class Tenant {
    @Id private UUID id;

    private String name;

    private String slug;

    private String status;

    private Instant createdAt;

    private Instant updatedAt;

    protected Tenant() {}

    public Tenant(
            UUID id,
            String name,
            String slug,
            String status,
            Instant createdAt,
            Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}

package ai.ontoflow.platform.user.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "PlatformUser")
@Table(name = "users")
public class User {
    @Id private UUID id;

    private String email;

    private String passwordHash;

    private String displayName;

    private String status;

    private Instant createdAt;

    protected User() {}

    public User(
            UUID id,
            String email,
            String passwordHash,
            String displayName,
            String status,
            Instant createdAt) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.status = status;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

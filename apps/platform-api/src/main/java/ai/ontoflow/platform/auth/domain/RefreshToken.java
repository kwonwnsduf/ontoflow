package ai.ontoflow.platform.auth.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity(name = "RefreshToken")
@Table(name = "refresh_tokens")
public class RefreshToken {
    @Id private UUID id;

    private UUID userId;

    private UUID familyId;

    private String tokenHash;

    private Instant expiresAt;

    private boolean consumed;

    private boolean revoked;

    protected RefreshToken() {}

    public RefreshToken(
            UUID id,
            UUID userId,
            UUID familyId,
            String tokenHash,
            Instant expiresAt,
            boolean consumed,
            boolean revoked) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.consumed = consumed;
        this.revoked = revoked;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isConsumed() {
        return consumed;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isUnusable(Instant now) {
        return consumed || revoked || !expiresAt.isAfter(now);
    }

    public void consume() {
        consumed = true;
    }
}

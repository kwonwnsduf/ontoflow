package ai.ontoflow.platform.common.security;

import java.time.Instant;
import java.util.UUID;

public record AuthPrincipal(
        UUID userId, UUID familyId, UUID tokenId, Instant issuedAt, Instant expiresAt) {}

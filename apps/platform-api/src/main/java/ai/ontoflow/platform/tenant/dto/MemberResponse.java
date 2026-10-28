package ai.ontoflow.platform.tenant.dto;

import ai.ontoflow.platform.tenant.domain.Role;

import java.util.UUID;

public record MemberResponse(UUID id, UUID userId, String email, String displayName, Role role) {}

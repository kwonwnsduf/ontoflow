package ai.ontoflow.platform.tenant.dto;

import ai.ontoflow.platform.tenant.domain.Role;

import java.util.UUID;

public record WorkspaceResponse(UUID id, String name, String slug, Role role) {}

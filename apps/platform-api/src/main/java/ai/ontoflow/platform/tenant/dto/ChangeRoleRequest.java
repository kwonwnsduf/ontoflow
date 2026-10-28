package ai.ontoflow.platform.tenant.dto;

import ai.ontoflow.platform.tenant.domain.Role;

import jakarta.validation.constraints.NotNull;

public record ChangeRoleRequest(@NotNull Role role) {}

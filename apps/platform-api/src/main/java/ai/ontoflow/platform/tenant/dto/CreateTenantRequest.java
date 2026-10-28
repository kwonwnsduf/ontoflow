package ai.ontoflow.platform.tenant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTenantRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 80) @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug) {}

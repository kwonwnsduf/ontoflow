package ai.ontoflow.platform.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 12, max = 72) @Pattern(regexp = "[\\x20-\\x7E]+") String password,
        @NotBlank @Size(max = 100) String displayName) {}

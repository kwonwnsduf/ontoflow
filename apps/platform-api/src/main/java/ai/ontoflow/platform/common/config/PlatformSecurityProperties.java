package ai.ontoflow.platform.common.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ontoflow.security")
public record PlatformSecurityProperties(@NotBlank @Size(min = 32) String internalApiKey) {}

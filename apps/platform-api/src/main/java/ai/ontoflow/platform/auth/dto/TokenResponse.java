package ai.ontoflow.platform.auth.dto;

import ai.ontoflow.platform.user.dto.UserResponse;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record TokenResponse(
        String accessToken,
        @JsonIgnore String refreshToken,
        long expiresIn,
        UserResponse user,
        @JsonIgnore long refreshExpiresIn) {}

package ai.ontoflow.platform.auth.service;

import ai.ontoflow.platform.auth.domain.RefreshToken;
import ai.ontoflow.platform.auth.dto.TokenResponse;
import ai.ontoflow.platform.auth.repository.RefreshTokenRepository;
import ai.ontoflow.platform.common.security.JwtTokenProvider;
import ai.ontoflow.platform.user.domain.User;
import ai.ontoflow.platform.user.repository.UserRepository;
import ai.ontoflow.platform.user.service.UserService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TokenService {
    private final RefreshTokenRepository refreshTokens;
    private final UserRepository users;
    private final JwtTokenProvider jwtTokens;
    private final AuthStateStore states;
    private final Duration accessTtl;
    private final Duration refreshTtl;
    private final SecureRandom random = new SecureRandom();

    public TokenService(
            RefreshTokenRepository refreshTokens,
            UserRepository users,
            JwtTokenProvider jwtTokens,
            AuthStateStore states,
            @Value("${ontoflow.auth.access-ttl:PT15M}") Duration accessTtl,
            @Value("${ontoflow.auth.refresh-ttl:P7D}") Duration refreshTtl) {
        this.refreshTokens = refreshTokens;
        this.users = users;
        this.jwtTokens = jwtTokens;
        this.states = states;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public TokenResponse issue(User user, UUID familyId) {
        return issue(user, familyId, Instant.now().plus(refreshTtl));
    }

    public TokenResponse issue(User user, UUID familyId, Instant refreshExpiresAt) {
        Instant now = Instant.now();
        String accessToken =
                jwtTokens.createAccessToken(user.getId(), familyId, now, now.plus(accessTtl));

        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        refreshTokens.save(
                new RefreshToken(
                        UUID.randomUUID(),
                        user.getId(),
                        familyId,
                        hash(refreshToken),
                        refreshExpiresAt,
                        false,
                        false));

        Duration remaining = Duration.between(now, refreshExpiresAt);
        states.activate(familyId, user.getId(), remaining);
        return new TokenResponse(
                accessToken,
                refreshToken,
                accessTtl.toSeconds(),
                UserService.response(user),
                remaining.toSeconds());
    }

    public RefreshToken lockRefreshToken(String rawToken) {
        RefreshToken token =
                refreshTokens
                        .findByTokenHash(hash(rawToken))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        // Lock the user to serialize rotations and revocations across the whole family.
        users.findByIdForUpdate(token.getUserId());
        refreshTokens.reload(token);
        return token;
    }

    public void revokeFamily(RefreshToken token) {
        revokeFamily(token.getFamilyId());
    }

    public void revokeFamily(UUID familyId) {
        refreshTokens.revokeFamily(familyId);
        states.revoke(familyId);
    }

    private String hash(String rawToken) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

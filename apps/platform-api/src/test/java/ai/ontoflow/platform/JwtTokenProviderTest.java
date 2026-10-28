package ai.ontoflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.JwtTokenProvider;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

class JwtTokenProviderTest {
    private final String secret = "test-jjwt-secret-at-least-32-bytes-long";
    private final SecretKey key =
            new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final JwtTokenProvider tokens = new JwtTokenProvider(secret);

    JwtBuilder validClaims() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("ontoflow")
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now.minusSeconds(1)))
                .expiration(Date.from(now.plusSeconds(900)));
    }

    String sign(JwtBuilder builder) {
        return builder.signWith(key, Jwts.SIG.HS256).compact();
    }

    @Test
    void issuesAndParsesHs256Tokens() {
        UUID userId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        Instant now = Instant.now();
        AuthPrincipal principal =
                tokens.parseAccessToken(
                        tokens.createAccessToken(userId, familyId, now, now.plusSeconds(900)));
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.familyId()).isEqualTo(familyId);
        assertThat(principal.tokenId()).isNotNull();
        assertThat(principal.expiresAt()).isAfter(principal.issuedAt());
    }

    @Test
    void rejectsExpiredAndWrongIssuerTokens() {
        assertThatThrownBy(
                        () ->
                                tokens.parseAccessToken(
                                        sign(
                                                validClaims()
                                                        .issuedAt(
                                                                Date.from(
                                                                        Instant.now()
                                                                                .minusSeconds(600)))
                                                        .expiration(
                                                                Date.from(
                                                                        Instant.now()
                                                                                .minusSeconds(
                                                                                        300))))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken(sign(validClaims().issuer("other"))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMissingRequiredClaimsAndInvalidIdentifiers() {
        assertThatThrownBy(() -> tokens.parseAccessToken(sign(validClaims().expiration(null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken(sign(validClaims().issuedAt(null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken(sign(validClaims().claim("sid", null))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(
                        () -> tokens.parseAccessToken(sign(validClaims().claim("sid", "invalid"))))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken(sign(validClaims().id(null))))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnsignedAndWrongKeyTokens() {
        assertThatThrownBy(() -> tokens.parseAccessToken(validClaims().compact()))
                .isInstanceOf(JwtException.class);
        SecretKey other =
                new SecretKeySpec(
                        "another-secret-that-is-at-least-32-bytes".getBytes(StandardCharsets.UTF_8),
                        "HmacSHA256");
        assertThatThrownBy(
                        () ->
                                tokens.parseAccessToken(
                                        validClaims().signWith(other, Jwts.SIG.HS256).compact()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsFutureIssueTimeAndUnsupportedAlgorithms() {
        assertThatThrownBy(
                        () ->
                                tokens.parseAccessToken(
                                        sign(
                                                validClaims()
                                                        .issuedAt(
                                                                Date.from(
                                                                        Instant.now()
                                                                                .plusSeconds(
                                                                                        120))))))
                .isInstanceOf(JwtException.class);
        String longSecret = secret.repeat(2);
        JwtTokenProvider longKeyProvider = new JwtTokenProvider(longSecret);
        SecretKey hs512 =
                new SecretKeySpec(longSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512");
        assertThatThrownBy(
                        () ->
                                longKeyProvider.parseAccessToken(
                                        validClaims().signWith(hs512, Jwts.SIG.HS512).compact()))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsShortKeysAndMalformedTokens() {
        assertThatThrownBy(() -> new JwtTokenProvider("short"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken("invalid-token"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.parseAccessToken("a".repeat(8193)))
                .isInstanceOf(JwtException.class);
    }
}

package ai.ontoflow.platform.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

@Component
public class JwtTokenProvider {
    private static final String ISSUER = "ontoflow";
    private static final long CLOCK_SKEW_SECONDS = 60;
    private final SecretKey key;
    private final JwtParser parser;

    public JwtTokenProvider(@Value("${ontoflow.auth.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("JWT_SECRET은 최소 32바이트여야 합니다.");
        }
        key = new SecretKeySpec(bytes, "HmacSHA256");
        parser =
                Jwts.parser()
                        .verifyWith(key)
                        .requireIssuer(ISSUER)
                        .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                        .build();
    }

    public String createAccessToken(
            UUID userId, UUID familyId, Instant issuedAt, Instant expiresAt) {
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim("sid", familyId.toString())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .id(UUID.randomUUID().toString())
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public AuthPrincipal parseAccessToken(String token) {
        if (token == null || token.isBlank() || token.length() > 8192) {
            throw new JwtException("유효하지 않은 접근 토큰입니다.");
        }
        Jws<Claims> signed = parser.parseSignedClaims(token);
        if (!"HS256".equals(signed.getHeader().getAlgorithm())) {
            throw new JwtException("허용하지 않는 서명 알고리즘입니다.");
        }
        Claims claims = signed.getPayload();
        Date issuedAt = claims.getIssuedAt();
        Date expiresAt = claims.getExpiration();
        if (issuedAt == null
                || expiresAt == null
                || !expiresAt.after(issuedAt)
                || issuedAt.toInstant().isAfter(Instant.now().plusSeconds(CLOCK_SKEW_SECONDS))) {
            throw new JwtException("토큰 발급 시각 또는 만료 시각이 올바르지 않습니다.");
        }
        try {
            return new AuthPrincipal(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("sid", String.class)),
                    UUID.fromString(claims.getId()),
                    issuedAt.toInstant(),
                    expiresAt.toInstant());
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new JwtException("필수 토큰 식별자가 올바르지 않습니다.", exception);
        }
    }
}

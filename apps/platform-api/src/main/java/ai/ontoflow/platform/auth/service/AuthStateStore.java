package ai.ontoflow.platform.auth.service;

import ai.ontoflow.platform.user.service.UserService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthStateStore {
    private static final DefaultRedisScript<Long> LIMIT =
            new DefaultRedisScript<>(
                    """
                    local count = tonumber(redis.call('GET', KEYS[1]) or '0')
                    if count >= tonumber(ARGV[1]) then
                        return math.max(redis.call('TTL', KEYS[1]), 1)
                    end
                    count = redis.call('INCR', KEYS[1])
                    if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
                    return 0
                    """,
                    Long.class);
    private final StringRedisTemplate redis;
    private final Duration refreshTtl;
    private final int loginAccountLimit;
    private final int loginIpLimit;
    private final int registerIpLimit;
    private final int refreshIpLimit;

    public AuthStateStore(
            StringRedisTemplate redis,
            @Value("${ontoflow.auth.refresh-ttl:P7D}") Duration refreshTtl,
            @Value("${ontoflow.auth.login-account-limit:10}") int loginAccountLimit,
            @Value("${ontoflow.auth.login-ip-limit:50}") int loginIpLimit,
            @Value("${ontoflow.auth.register-ip-limit:10}") int registerIpLimit,
            @Value("${ontoflow.auth.refresh-ip-limit:120}") int refreshIpLimit) {
        this.redis = redis;
        this.refreshTtl = refreshTtl;
        this.loginAccountLimit = loginAccountLimit;
        this.loginIpLimit = loginIpLimit;
        this.registerIpLimit = registerIpLimit;
        this.refreshIpLimit = refreshIpLimit;
        if (loginAccountLimit < 1
                || loginIpLimit < 1
                || registerIpLimit < 1
                || refreshIpLimit < 1
                || refreshTtl.isNegative()
                || refreshTtl.isZero()) {
            throw new IllegalArgumentException("Authentication limits and TTL must be positive");
        }
    }

    public void beforeLogin(String email, String ip) {
        limit("login:ip", ip, loginIpLimit, Duration.ofMinutes(15));
        limit(
                "login:account",
                UserService.normalizeEmail(email),
                loginAccountLimit,
                Duration.ofMinutes(15));
    }

    public void beforeRegister(String ip) {
        limit("register:ip", ip, registerIpLimit, Duration.ofHours(1));
    }

    public void beforeRefresh(String ip) {
        limit("refresh:ip", ip, refreshIpLimit, Duration.ofMinutes(1));
    }

    public void activate(UUID familyId, UUID userId, Duration remaining) {
        try {
            redis.opsForValue()
                    .set(sessionKey(familyId), userId.toString(), remaining.plusSeconds(60));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void revoke(UUID familyId) {
        try {
            redis.opsForValue().set(sessionKey(familyId), "revoked", refreshTtl.plusSeconds(60));
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    public void requireActive(UUID familyId, UUID userId) {
        try {
            String owner = redis.opsForValue().get(sessionKey(familyId));
            if (!userId.toString().equals(owner)) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Login session expired or revoked");
            }
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private void limit(String scope, String identity, int maximum, Duration window) {
        try {
            Long retry =
                    redis.execute(
                            LIMIT,
                            List.of("auth:limit:" + scope + ":" + hash(identity)),
                            Integer.toString(maximum),
                            Long.toString(window.toSeconds()));
            if (retry == null) throw new IllegalStateException("Missing rate limit result");
            if (retry > 0) throw new LoginRateLimitException(retry);
        } catch (DataAccessException exception) {
            throw unavailable(exception);
        }
    }

    private String sessionKey(UUID familyId) {
        return "auth:session:" + familyId;
    }

    private String hash(String identity) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private ResponseStatusException unavailable(Exception cause) {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Authentication service temporarily unavailable",
                cause);
    }
}

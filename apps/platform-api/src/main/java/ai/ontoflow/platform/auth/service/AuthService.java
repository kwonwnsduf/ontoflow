package ai.ontoflow.platform.auth.service;

import ai.ontoflow.platform.audit.service.AuditEventService;
import ai.ontoflow.platform.auth.domain.RefreshToken;
import ai.ontoflow.platform.auth.dto.LoginRequest;
import ai.ontoflow.platform.auth.dto.RegisterRequest;
import ai.ontoflow.platform.auth.dto.TokenResponse;
import ai.ontoflow.platform.user.domain.User;
import ai.ontoflow.platform.user.repository.UserRepository;
import ai.ontoflow.platform.user.service.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class AuthService {
    private static final String UNKNOWN_USER_PASSWORD_HASH =
            "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    private final UserRepository users;
    private final UserService userService;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final AuditEventService auditEvents;

    public AuthService(
            UserRepository users,
            UserService userService,
            PasswordEncoder passwords,
            TokenService tokens,
            AuditEventService auditEvents) {
        this.users = users;
        this.userService = userService;
        this.passwords = passwords;
        this.tokens = tokens;
        this.auditEvents = auditEvents;
    }

    public TokenResponse register(RegisterRequest request) {
        String email = UserService.normalizeEmail(request.email());
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }

        User user =
                new User(
                        UUID.randomUUID(),
                        email,
                        passwords.encode(request.password()),
                        request.displayName().strip(),
                        "ACTIVE",
                        Instant.now());
        users.save(user);
        auditEvents.record(null, user.getId(), "AUTH_REGISTER", user.getId());
        return tokens.issue(user, UUID.randomUUID());
    }

    public TokenResponse login(LoginRequest request) {
        User user = users.findByEmail(UserService.normalizeEmail(request.email())).orElse(null);
        String passwordHash = user == null ? UNKNOWN_USER_PASSWORD_HASH : user.getPasswordHash();

        // Compare a hash even for unknown accounts.
        boolean passwordMatches = passwords.matches(request.password(), passwordHash);
        if (!passwordMatches || user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        auditEvents.record(null, user.getId(), "AUTH_LOGIN", user.getId());
        return tokens.issue(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = RefreshReplayException.class)
    public TokenResponse refresh(String rawToken) {
        RefreshToken token = tokens.lockRefreshToken(rawToken);
        if (token.isUnusable(Instant.now())) {
            tokens.revokeFamily(token);
            auditEvents.record(
                    null, token.getUserId(), "AUTH_REFRESH_REJECTED", token.getFamilyId());
            // Commit the revocation even though this request returns 401.
            throw new RefreshReplayException();
        }

        token.consume();
        auditEvents.record(null, token.getUserId(), "AUTH_REFRESH", token.getFamilyId());
        return tokens.issue(
                userService.requireActive(token.getUserId()),
                token.getFamilyId(),
                token.getExpiresAt());
    }

    public UUID logout(String rawToken) {
        RefreshToken token = tokens.lockRefreshToken(rawToken);
        tokens.revokeFamily(token);
        auditEvents.record(null, token.getUserId(), "AUTH_LOGOUT", token.getFamilyId());
        return token.getFamilyId();
    }

    public void logoutFamily(UUID userId, UUID familyId) {
        users.findByIdForUpdate(userId);
        userService.requireActive(userId);
        tokens.revokeFamily(familyId);
        auditEvents.record(null, userId, "AUTH_LOGOUT", familyId);
    }
}

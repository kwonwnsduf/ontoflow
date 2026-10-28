package ai.ontoflow.platform.auth.service;

import ai.ontoflow.platform.auth.repository.RefreshTokenRepository;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

@Service
public class RefreshTokenCleanup {
    private final RefreshTokenRepository tokens;

    public RefreshTokenCleanup(RefreshTokenRepository tokens) {
        this.tokens = tokens;
    }

    @Scheduled(initialDelayString = "PT1H", fixedDelayString = "PT1H")
    @Transactional
    public void removeExpired() {
        // Keep expired families for seven additional days for incident investigation.
        tokens.deleteExpiredBefore(Instant.now().minus(Duration.ofDays(7)));
    }
}

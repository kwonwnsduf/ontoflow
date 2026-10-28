package ai.ontoflow.platform.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class LoginRateLimitException extends ResponseStatusException {
    private final long retryAfter;

    public LoginRateLimitException(long retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "Too many authentication attempts");
        this.retryAfter = retryAfter;
    }

    public long getRetryAfter() {
        return retryAfter;
    }
}

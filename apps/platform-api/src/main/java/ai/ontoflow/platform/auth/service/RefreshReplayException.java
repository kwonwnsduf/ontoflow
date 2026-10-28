package ai.ontoflow.platform.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class RefreshReplayException extends ResponseStatusException {
    public RefreshReplayException() {
        super(HttpStatus.UNAUTHORIZED, "Expired, revoked or reused refresh token");
    }
}

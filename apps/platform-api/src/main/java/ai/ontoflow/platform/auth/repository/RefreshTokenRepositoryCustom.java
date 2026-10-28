package ai.ontoflow.platform.auth.repository;

import ai.ontoflow.platform.auth.domain.RefreshToken;

public interface RefreshTokenRepositoryCustom {
    void reload(RefreshToken token);
}

package ai.ontoflow.platform.auth.repository;

import ai.ontoflow.platform.auth.domain.RefreshToken;

import jakarta.persistence.EntityManager;

public class RefreshTokenRepositoryCustomImpl implements RefreshTokenRepositoryCustom {
    private final EntityManager entityManager;

    public RefreshTokenRepositoryCustomImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public void reload(RefreshToken token) {
        // Read the latest token state after acquiring the user row lock.
        entityManager.refresh(token);
    }
}

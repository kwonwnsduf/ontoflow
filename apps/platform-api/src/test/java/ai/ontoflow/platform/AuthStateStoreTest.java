package ai.ontoflow.platform;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.ontoflow.platform.auth.service.AuthStateStore;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.UUID;

class AuthStateStoreTest {
    @Test
    void translatesConnectionFailuresToServiceUnavailable() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("offline"));
        AuthStateStore states = new AuthStateStore(redis, Duration.ofDays(7), 10, 50, 10, 120);
        assertThatThrownBy(() -> states.requireActive(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception ->
                                org.assertj.core.api.Assertions.assertThat(
                                                exception.getStatusCode().value())
                                        .isEqualTo(503));
    }
}

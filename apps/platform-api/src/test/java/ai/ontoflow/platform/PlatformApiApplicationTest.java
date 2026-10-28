package ai.ontoflow.platform;

import static org.assertj.core.api.Assertions.assertThat;

import ai.ontoflow.platform.common.trace.TraceIdFilter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PlatformApiApplicationTest {
    @Autowired TraceIdFilter traceIdFilter;

    @Test
    void contextLoads() {
        assertThat(traceIdFilter).isNotNull();
    }
}

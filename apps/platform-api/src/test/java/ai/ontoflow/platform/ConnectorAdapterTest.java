package ai.ontoflow.platform;

import ai.ontoflow.platform.connector.EnterpriseAdapter;
import ai.ontoflow.platform.connector.LocalSecretStore;
import ai.ontoflow.platform.connector.MockEnterpriseAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorAdapterTest {
    @Test
    void enterpriseTimeoutReturnsGatewayTimeout() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/systems/alpha/payments", exchange -> {
            try { Thread.sleep(2500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try {
            MockEnterpriseAdapter adapter = new MockEnterpriseAdapter(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
            ResponseStatusException error = assertThrows(ResponseStatusException.class,
                    () -> adapter.forType("ALPHA").failedPayments("alpha-token"));
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, error.getStatusCode());
        } finally { server.stop(0); }
    }

    @Test
    void encryptedCredentialsRoundTripAndRejectWrongKey() {
        LocalSecretStore first = new LocalSecretStore("test-secret-one-with-more-than-32-characters");
        LocalSecretStore second = new LocalSecretStore("test-secret-two-with-more-than-32-characters");
        String encrypted = first.seal("enterprise-credential");
        assertNotEquals("enterprise-credential", encrypted);
        assertEquals("enterprise-credential", first.open(encrypted));
        assertThrows(IllegalStateException.class, () -> second.open(encrypted));
    }

    @Test
    void adaptersNormalizeDifferentPaymentStatusesAndRejectBadCredentials() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/systems/", exchange -> {
            String system = exchange.getRequestURI().getPath().split("/")[2];
            String expected = "Bearer " + system + "-token";
            int status = expected.equals(exchange.getRequestHeaders().getFirst("Authorization")) ? 200 : 401;
            String body = status == 401 ? "{\"error\":\"invalid_credential\"}" :
                    system.equals("alpha")
                            ? "{\"items\":[{\"id\":\"A-P1\",\"customerId\":\"A-C1\",\"amount\":42,\"currency\":\"KRW\",\"status\":\"FAILED\",\"failedAt\":\"2026-10-20T00:00:00Z\"}]}"
                            : "{\"items\":[{\"id\":\"B-P1\",\"customerId\":\"B-C1\",\"amount\":42,\"currency\":\"KRW\",\"status\":\"DECLINED\",\"failedAt\":\"2026-10-20T00:00:00Z\"}]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            MockEnterpriseAdapter factory = new MockEnterpriseAdapter(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
            List<EnterpriseAdapter.Payment> alpha = factory.forType("ALPHA").failedPayments("alpha-token");
            List<EnterpriseAdapter.Payment> beta = factory.forType("BETA").failedPayments("beta-token");
            assertEquals("FAILED", alpha.getFirst().status());
            assertEquals("FAILED", beta.getFirst().status());
            assertEquals("A-P1", alpha.getFirst().id());
            assertEquals("B-P1", beta.getFirst().id());
            assertThrows(ResponseStatusException.class,
                    () -> factory.forType("ALPHA").failedPayments("beta-token"));
        } finally {
            server.stop(0);
        }
    }
}

package ai.ontoflow.platform;

import ai.ontoflow.platform.connector.SecretStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConnectorSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate db;
    @Autowired SecretStore secrets;

    private JsonNode postJson(String path, Map<String, ?> input, String token, int status) throws Exception {
        var request = post("/api/v1" + path).with(csrf().asHeader()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mapper.readTree(mvc.perform(request).andExpect(status().is(status))
                .andReturn().getResponse().getContentAsString());
    }

    @Test
    void connectorReadsAreTenantScopedAndDisabledCapabilitiesCannotExecute() throws Exception {
        JsonNode a = postJson("/auth/register", Map.of("email", UUID.randomUUID() + "@example.com",
                "password", "Strong-password-123", "displayName", "A"), null, 201);
        JsonNode b = postJson("/auth/register", Map.of("email", UUID.randomUUID() + "@example.com",
                "password", "Strong-password-123", "displayName", "B"), null, 201);
        String aToken = a.path("accessToken").asText();
        String bToken = b.path("accessToken").asText();
        String tenantA = postJson("/tenants", Map.of("name", "A", "slug", "connector-a-" + UUID.randomUUID()),
                aToken, 201).path("id").asText();
        String tenantB = postJson("/tenants", Map.of("name", "B", "slug", "connector-b-" + UUID.randomUUID()),
                bToken, 201).path("id").asText();
        UUID connector = UUID.randomUUID();
        db.update("INSERT INTO connectors (id,tenant_id,system_type,name,credential_ciphertext,status,created_at) VALUES (?,?,?,?,?,?,?)",
                connector, UUID.fromString(tenantA), "ALPHA", "Alpha", secrets.seal("alpha-token"),
                "CONNECTED", Timestamp.from(Instant.now()));
        db.update("INSERT INTO connector_capabilities (connector_id,action,enabled) VALUES (?,?,false)",
                connector, "payment.list_failed");
        String pathA = "/api/v1/tenants/" + tenantA + "/connectors/" + connector;
        mvc.perform(get(pathA).header("Authorization", "Bearer " + aToken)).andExpect(status().isOk());
        mvc.perform(get(pathA).header("Authorization", "Bearer " + bToken)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenants/" + tenantB + "/connectors/" + connector)
                .header("Authorization", "Bearer " + bToken)).andExpect(status().isNotFound());
        mvc.perform(get(pathA + "/payments/failed").header("Authorization", "Bearer " + aToken))
                .andExpect(status().isForbidden());
        String body = mvc.perform(get(pathA).header("Authorization", "Bearer " + aToken))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("alpha-token"));
    }
}

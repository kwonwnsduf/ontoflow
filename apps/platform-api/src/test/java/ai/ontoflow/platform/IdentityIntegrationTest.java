package ai.ontoflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.ontoflow.platform.user.domain.User;

import com.fasterxml.jackson.databind.*;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @org.springframework.beans.factory.annotation.Value("${ontoflow.auth.jwt-secret}")
    String jwtSecret;

    final String password = "Strong-password-123";

    JsonNode json(MvcResult r) throws Exception {
        JsonNode body = mapper.readTree(r.getResponse().getContentAsString());
        // Test-only access to the HttpOnly response cookie; the JSON API never returns it.
        for (String header : r.getResponse().getHeaders("Set-Cookie")) {
            MockCookie cookie = MockCookie.parse(header);
            if (cookie.getName().equals("ontoflow_refresh") && !cookie.getValue().isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) body)
                        .put("refreshToken", cookie.getValue());
            }
        }
        return body;
    }

    JsonNode postJson(String url, Object body, String token, int status) throws Exception {
        var request =
                post("/api/v1" + url)
                        .with(csrf().asHeader())
                        .contentType(MediaType.APPLICATION_JSON);
        if (url.equals("/auth/refresh") || url.equals("/auth/logout")) {
            Object raw = ((Map<?, ?>) body).get("refreshToken");
            request.cookie(new Cookie("ontoflow_refresh", raw.toString()));
        } else {
            request.content(mapper.writeValueAsString(body));
        }
        if (token != null) request.header("Authorization", "Bearer " + token);
        return json(mvc.perform(request).andExpect(status().is(status)).andReturn());
    }

    JsonNode register(String email) throws Exception {
        return postJson(
                "/auth/register",
                Map.of("email", email, "password", password, "displayName", "Tester"),
                null,
                201);
    }

    String access(JsonNode session) {
        return session.path("accessToken").asText();
    }

    String refresh(JsonNode session) {
        return session.path("refreshToken").asText();
    }

    String email() {
        return UUID.randomUUID() + "@example.com";
    }

    void getStatus(String url, String token, int status) throws Exception {
        mvc.perform(get("/api/v1" + url).header("Authorization", "Bearer " + token))
                .andExpect(status().is(status))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void authenticationRotationReplayAndLogout() throws Exception {
        String mail = email();
        JsonNode first = register(mail);
        postJson(
                "/auth/register",
                Map.of("email", mail, "password", password, "displayName", "Duplicate"),
                null,
                409);
        postJson("/auth/login", Map.of("email", mail, "password", "wrong-password"), null, 401);
        JsonNode login =
                postJson(
                        "/auth/login",
                        Map.of("email", mail.toUpperCase(Locale.ROOT), "password", password),
                        null,
                        200);
        getStatus("/auth/me", access(login), 200);
        JsonNode rotated =
                postJson("/auth/refresh", Map.of("refreshToken", refresh(login)), null, 200);
        assertThat(refresh(rotated)).isNotEqualTo(refresh(login));
        postJson("/auth/refresh", Map.of("refreshToken", refresh(login)), null, 401);
        postJson("/auth/refresh", Map.of("refreshToken", refresh(rotated)), null, 401);
        // Separate login families remain valid after replay revocation.
        postJson("/auth/refresh", Map.of("refreshToken", refresh(first)), null, 200);
        JsonNode last =
                postJson("/auth/login", Map.of("email", mail, "password", password), null, 200);
        mvc.perform(
                        post("/api/v1/auth/logout")
                                .with(csrf().asHeader())
                                .cookie(new Cookie("ontoflow_refresh", refresh(last)))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("refreshToken", refresh(last)))))
                .andExpect(status().isNoContent());
        postJson("/auth/refresh", Map.of("refreshToken", refresh(last)), null, 401);
        tx.executeWithoutResult(
                s -> {
                    User user =
                            em.find(
                                    User.class,
                                    UUID.fromString(first.path("user").path("id").asText()));
                    assertThat(user.getPasswordHash()).startsWith("$2a$").isNotEqualTo(password);
                });
    }

    @Test
    void rejectsExpiredAndTamperedTokens() throws Exception {
        JsonNode session = register(email());
        Instant now = Instant.now();
        String expired =
                io.jsonwebtoken.Jwts.builder()
                        .issuer("ontoflow")
                        .subject(session.path("user").path("id").asText())
                        .claim("sid", UUID.randomUUID().toString())
                        .id(UUID.randomUUID().toString())
                        .issuedAt(Date.from(now.minusSeconds(600)))
                        .expiration(Date.from(now.minusSeconds(300)))
                        .signWith(
                                new javax.crypto.spec.SecretKeySpec(
                                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                        "HmacSHA256"),
                                io.jsonwebtoken.Jwts.SIG.HS256)
                        .compact();
        getStatus("/auth/me", expired, 401);
        getStatus("/auth/me", access(session) + "tamper", 401);
        tx.executeWithoutResult(
                s ->
                        em.createQuery(
                                        "update RefreshToken set expiresAt = :time where userId ="
                                                + " :user")
                                .setParameter("time", now.minusSeconds(10))
                                .setParameter(
                                        "user",
                                        UUID.fromString(session.path("user").path("id").asText()))
                                .executeUpdate());
        postJson("/auth/refresh", Map.of("refreshToken", refresh(session)), null, 401);
    }

    @Test
    void tenantIsolationRolesAndLastOwner() throws Exception {
        JsonNode a = register(email());
        String bEmail = email();
        JsonNode b = register(bEmail);
        String tenant =
                postJson(
                                "/tenants",
                                Map.of("name", "Tenant A", "slug", "a-" + UUID.randomUUID()),
                                access(a),
                                201)
                        .path("id")
                        .asText();
        String other =
                postJson(
                                "/tenants",
                                Map.of("name", "Tenant B", "slug", "b-" + UUID.randomUUID()),
                                access(b),
                                201)
                        .path("id")
                        .asText();
        getStatus("/tenants/" + tenant, access(b), 403);
        getStatus("/tenants/" + other, access(a), 403);
        mvc.perform(
                        get("/api/v1/tenants/" + tenant)
                                .header("Authorization", "Bearer " + access(b))
                                .header("X-Tenant-Id", tenant))
                .andExpect(status().isForbidden());
        postJson("/tenants/" + tenant + "/switch", Map.of(), access(b), 403);
        JsonNode list =
                json(
                        mvc.perform(
                                        get("/api/v1/tenants")
                                                .header("Authorization", "Bearer " + access(b)))
                                .andExpect(status().isOk())
                                .andReturn());
        assertThat(list.path("items").size()).isEqualTo(1);
        assertThat(list.path("items").get(0).path("id").asText()).isEqualTo(other);
        JsonNode member =
                postJson(
                        "/tenants/" + tenant + "/members",
                        Map.of("email", bEmail, "role", "VIEWER"),
                        access(a),
                        201);
        getStatus("/tenants/" + tenant, access(b), 200);
        getStatus("/tenants/" + tenant + "/members", access(b), 403);
        getStatus("/tenants/" + tenant + "/audit-logs", access(b), 403);
        postJson(
                "/tenants/" + tenant + "/members",
                Map.of("email", bEmail, "role", "ADMIN"),
                access(b),
                403);
        String memberPath = "/api/v1/tenants/" + tenant + "/members/" + member.path("id").asText();
        for (String role : List.of("OPERATOR", "ADMIN")) {
            mvc.perform(
                            patch(memberPath)
                                    .header("Authorization", "Bearer " + access(a))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"role\":\"" + role + "\"}"))
                    .andExpect(status().isNoContent());
            getStatus(
                    "/tenants/" + tenant + "/members", access(b), role.equals("ADMIN") ? 200 : 403);
        }
        mvc.perform(
                        patch(memberPath)
                                .header("Authorization", "Bearer " + access(b))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isForbidden());
        JsonNode members =
                json(
                        mvc.perform(
                                        get("/api/v1/tenants/" + tenant + "/members")
                                                .header("Authorization", "Bearer " + access(a)))
                                .andReturn());
        String ownerId = "";
        for (JsonNode m : members.path("items"))
            if (m.path("role").asText().equals("OWNER")) ownerId = m.path("id").asText();
        mvc.perform(
                        delete("/api/v1/tenants/" + tenant + "/members/" + ownerId)
                                .header("Authorization", "Bearer " + access(a)))
                .andExpect(status().isConflict());
        mvc.perform(
                        delete(
                                        "/api/v1/tenants/"
                                                + other
                                                + "/members/"
                                                + member.path("id").asText())
                                .header("Authorization", "Bearer " + access(b)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(memberPath).header("Authorization", "Bearer " + access(a)))
                .andExpect(status().isNoContent());
        getStatus("/tenants/" + tenant, access(b), 403);
        getStatus("/tenants?page=-1", access(a), 400);
        getStatus("/tenants/" + tenant + "/audit-logs", access(a), 200);
    }

    @Test
    void concurrentRefreshRevokesTheRotatedFamily() throws Exception {
        JsonNode session = register(email());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<MvcResult> rotate =
                () -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Refresh requests did not start");
                    }
                    return mvc.perform(
                                    post("/api/v1/auth/refresh")
                                            .with(csrf().asHeader())
                                            .cookie(
                                                    new Cookie(
                                                            "ontoflow_refresh", refresh(session)))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    mapper.writeValueAsString(
                                                            Map.of(
                                                                    "refreshToken",
                                                                    refresh(session)))))
                            .andReturn();
                };

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(rotate);
            var second = executor.submit(rotate);
            try {
                assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            MvcResult firstResult = first.get(15, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(15, TimeUnit.SECONDS);
            assertThat(
                            List.of(
                                    firstResult.getResponse().getStatus(),
                                    secondResult.getResponse().getStatus()))
                    .containsExactlyInAnyOrder(200, 401);

            MvcResult winner =
                    firstResult.getResponse().getStatus() == 200 ? firstResult : secondResult;
            postJson("/auth/refresh", Map.of("refreshToken", refresh(json(winner))), null, 401);
        }
    }

    @Test
    void workspaceMemberAndAuditPaginationPreservesTotals() throws Exception {
        JsonNode owner = register(email());
        List<String> tenantIds = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            tenantIds.add(
                    postJson(
                                    "/tenants",
                                    Map.of(
                                            "name",
                                            "Workspace " + index,
                                            "slug",
                                            "page-" + UUID.randomUUID()),
                                    access(owner),
                                    201)
                            .path("id")
                            .asText());
        }
        JsonNode tenantPage = getJson("/tenants?page=1&size=1", access(owner));
        assertThat(tenantPage.path("total").asInt()).isEqualTo(3);
        assertThat(tenantPage.path("items").size()).isEqualTo(1);
        assertThat(tenantPage.path("items").get(0).path("id").asText()).isEqualTo(tenantIds.get(1));

        String tenant = tenantIds.get(0);
        String inviteeEmail = email();
        register(inviteeEmail);
        JsonNode member =
                postJson(
                        "/tenants/" + tenant + "/members",
                        Map.of("email", inviteeEmail, "role", "VIEWER"),
                        access(owner),
                        201);
        JsonNode memberPage =
                getJson("/tenants/" + tenant + "/members?page=1&size=1", access(owner));
        assertThat(memberPage.path("total").asInt()).isEqualTo(2);
        assertThat(memberPage.path("items").get(0).path("id").asText())
                .isEqualTo(member.path("id").asText());

        String auditPath = "/tenants/" + tenant + "/audit-logs";
        JsonNode auditPage = getJson(auditPath + "?page=0&size=1", access(owner));
        assertThat(auditPage.path("total").asInt()).isEqualTo(2);
        assertThat(auditPage.path("items").get(0).path("action").asText()).isEqualTo("MEMBER_ADD");
        JsonNode emptyPage = getJson(auditPath + "?page=2&size=1", access(owner));
        assertThat(emptyPage.path("total").asInt()).isEqualTo(2);
        assertThat(emptyPage.path("items").size()).isZero();
    }

    JsonNode getJson(String path, String token) throws Exception {
        return json(
                mvc.perform(get("/api/v1" + path).header("Authorization", "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn());
    }

    @Test
    void validationAndTrace() throws Exception {
        postJson("/auth/refresh", Map.of("refreshToken", "unknown-token"), null, 401);
        mvc.perform(get("/api/v1/tenants").header("X-Trace-Id", "test-trace"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.traceId").value("test-trace"));
        postJson(
                "/auth/register",
                Map.of("email", "invalid", "password", "short", "displayName", ""),
                null,
                400);
    }
}

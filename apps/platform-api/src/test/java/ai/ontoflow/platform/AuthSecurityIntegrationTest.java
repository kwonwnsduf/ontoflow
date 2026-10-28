package ai.ontoflow.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.ontoflow.platform.auth.controller.AuthCookies;
import ai.ontoflow.platform.auth.service.AuthStateStore;
import ai.ontoflow.platform.auth.service.LoginRateLimitException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired StringRedisTemplate redis;
    @MockitoSpyBean AuthStateStore states;

    MockHttpServletRequestBuilder mutation(String path) throws Exception {
        MvcResult csrf =
                mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        JsonNode token = mapper.readTree(csrf.getResponse().getContentAsString());
        return post("/api/v1/auth/" + path)
                .cookie(cookie(csrf, "ontoflow_csrf"))
                .header(token.path("headerName").asText(), token.path("token").asText());
    }

    MockCookie cookie(MvcResult result, String name) {
        return result.getResponse().getHeaders("Set-Cookie").stream()
                .map(MockCookie::parse)
                .filter(value -> value.getName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    MvcResult register() throws Exception {
        return mvc.perform(
                        mutation("register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of(
                                                        "email",
                                                        UUID.randomUUID() + "@example.com",
                                                        "password",
                                                        "Secure-password-123",
                                                        "displayName",
                                                        "Tester"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.refreshExpiresIn").doesNotExist())
                .andReturn();
    }

    String access(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString())
                .path("accessToken")
                .asText();
    }

    @Test
    void cookiesRestoreTheSessionAndLogoutImmediatelyRejectsAllIssuedAccessTokens()
            throws Exception {
        MvcResult login = register();
        MockCookie original = cookie(login, "ontoflow_refresh");
        assertThat(original.isHttpOnly()).isTrue();
        assertThat(original.getSameSite()).isEqualTo("Strict");
        assertThat(original.getMaxAge()).isBetween(604790, 604800);
        MvcResult refreshed =
                mvc.perform(mutation("refresh").cookie(original))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.refreshToken").doesNotExist())
                        .andReturn();
        assertThat(cookie(refreshed, "ontoflow_refresh").getValue())
                .isNotEqualTo(original.getValue());
        assertThat(cookie(refreshed, "ontoflow_refresh").getMaxAge())
                .isLessThanOrEqualTo(original.getMaxAge());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(refreshed)))
                .andExpect(status().isOk());
        mvc.perform(mutation("logout").cookie(cookie(refreshed, "ontoflow_refresh")))
                .andExpect(status().isNoContent())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                                .maxAge("ontoflow_refresh", 0));
        for (String token : new String[] {access(login), access(refreshed)}) {
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(mutation("refresh").cookie(cookie(refreshed, "ontoflow_refresh")))
                .andExpect(status().isUnauthorized())
                .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                                .maxAge("ontoflow_refresh", 0));
    }

    @Test
    void rejectsMissingCsrfTamperedCsrfBodyRefreshTokensAndUntrustedOrigins() throws Exception {
        mvc.perform(
                        post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(
                        mutation("refresh")
                                .with(
                                        request -> {
                                            request.removeHeader("X-XSRF-TOKEN");
                                            request.addHeader("X-XSRF-TOKEN", "tampered");
                                            return request;
                                        }))
                .andExpect(status().isForbidden());
        mvc.perform(
                        mutation("refresh")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"refreshToken\":\"body-is-not-accepted\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/csrf").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/auth/csrf").header("Origin", "http://localhost:3000"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void redisOutageFailsClosedAndFailedRotationRollsBack() throws Exception {
        MvcResult login = register();
        try {
            doThrow(
                            new ResponseStatusException(
                                    HttpStatus.SERVICE_UNAVAILABLE, "Authentication unavailable"))
                    .when(states)
                    .requireActive(any(), any());
            mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(login)))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            reset(states);
        }
        try {
            doThrow(
                            new ResponseStatusException(
                                    HttpStatus.SERVICE_UNAVAILABLE, "Authentication unavailable"))
                    .when(states)
                    .activate(any(), any(), any());
            mvc.perform(mutation("refresh").cookie(cookie(login, "ontoflow_refresh")))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            reset(states);
        }
        // The same token must remain usable after the failed Redis write rolled back the DB
        // rotation.
        mvc.perform(mutation("refresh").cookie(cookie(login, "ontoflow_refresh")))
                .andExpect(status().isOk());
    }

    @Test
    void redisDataLossRequiresAValidPersistentRefreshToken() throws Exception {
        MvcResult login = register();
        String sessionId =
                mapper.readTree(
                                new String(
                                        java.util.Base64.getUrlDecoder()
                                                .decode(access(login).split("\\.")[1]),
                                        java.nio.charset.StandardCharsets.UTF_8))
                        .path("sid")
                        .asText();
        redis.delete("auth:session:" + sessionId);
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(login)))
                .andExpect(status().isUnauthorized());
        MvcResult restored =
                mvc.perform(mutation("refresh").cookie(cookie(login, "ontoflow_refresh")))
                        .andExpect(status().isOk())
                        .andReturn();
        mvc.perform(mutation("logout").cookie(cookie(restored, "ontoflow_refresh")))
                .andExpect(status().isNoContent());
        redis.delete("auth:session:" + sessionId);
        mvc.perform(mutation("refresh").cookie(cookie(restored, "ontoflow_refresh")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rateLimitsAreSharedByNormalizedAccountAndRemoteIp() {
        AuthStateStore limiter = new AuthStateStore(redis, Duration.ofDays(7), 2, 3, 2, 2);
        String account = UUID.randomUUID() + "@example.com";
        limiter.beforeLogin(account, UUID.randomUUID().toString());
        limiter.beforeLogin(account.toUpperCase(), UUID.randomUUID().toString());
        assertThatThrownBy(() -> limiter.beforeLogin(account, UUID.randomUUID().toString()))
                .isInstanceOfSatisfying(
                        LoginRateLimitException.class,
                        exception -> assertThat(exception.getRetryAfter()).isBetween(1L, 900L));
        String ip = UUID.randomUUID().toString();
        for (int index = 0; index < 3; index++)
            limiter.beforeLogin(UUID.randomUUID() + "@example.com", ip);
        assertThatThrownBy(() -> limiter.beforeLogin(UUID.randomUUID() + "@example.com", ip))
                .isInstanceOf(LoginRateLimitException.class);
    }

    @Test
    void logoutCanRevokeTheSignedInFamilyEvenWhenItsRefreshCookieIsMissing() throws Exception {
        MvcResult login = register();
        mvc.perform(mutation("logout").header("Authorization", "Bearer " + access(login)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access(login)))
                .andExpect(status().isUnauthorized());
        mvc.perform(mutation("refresh").cookie(cookie(login, "ontoflow_refresh")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void productionCookieIsSecureHttpOnlyAndHostScoped() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new AuthCookies(true).write(response, "secret", 900);
        String header = response.getHeader("Set-Cookie");
        assertThat(header)
                .contains(
                        "__Host-ontoflow_refresh=secret",
                        "Secure",
                        "HttpOnly",
                        "SameSite=Strict",
                        "Path=/")
                .doesNotContain("Domain=");
    }
}

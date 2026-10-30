package ai.ontoflow.platform;

import ai.ontoflow.platform.connector.ExternalSourceService;
import ai.ontoflow.platform.connector.SourceCatalog;
import ai.ontoflow.platform.connector.SourceReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
class ExternalSourceIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired SourceCatalog catalog;
    @Autowired ExternalSourceService sources;
    @Autowired ObjectMapper json;
    @MockitoBean SourceReader reader;

    private UUID[] workspace() {
        UUID user = UUID.randomUUID(), tenant = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        db.update("INSERT INTO users (id,email,password_hash,display_name,status,created_at) VALUES (?,?,?,?,?,?)",
                user, user + "@example.com", "unused", "Tester", "ACTIVE", now);
        db.update("INSERT INTO tenants (id,name,slug,status,created_at,updated_at) VALUES (?,?,?,?,?,?)",
                tenant, "Workspace", "source-" + tenant, "ACTIVE", now, now);
        db.update("INSERT INTO tenant_members (id,tenant_id,user_id,role,created_at) VALUES (?,?,?,?,?)",
                UUID.randomUUID(), tenant, user, "OWNER", now);
        return new UUID[]{user, tenant};
    }

    @Test
    void sourceRecordsAreScopedAndDeletionProducesEvents() {
        UUID[] a = workspace(), b = workspace();
        String key = "test_resource_" + UUID.randomUUID().toString().replace("-", "");
        SourceCatalog.Resource resource = new SourceCatalog.Resource();
        resource.setType("POSTGRESQL");
        resource.setJdbcUrl("jdbc:postgresql://example.invalid/sample");
        resource.setView("public.approved_view");
        resource.setIdColumn("id");
        catalog.getResources().put(key, resource);
        var item = new SourceReader.Item("record-1", null, null, json.createObjectNode().put("id", "record-1"));
        when(reader.read(eq("POSTGRESQL"), eq(resource), anyString(), isNull(), anyInt()))
                .thenReturn(new SourceReader.Page(List.of(item), null, "public.approved_view"))
                .thenReturn(new SourceReader.Page(List.of(item), null, "public.approved_view"))
                .thenReturn(new SourceReader.Page(List.of(), null, "public.approved_view"));

        var source = sources.connect(a[0], a[1], new ExternalSourceService.ConnectRequest(
                "POSTGRESQL", "Approved", key, "{\"username\":\"reader\",\"password\":\"secret\"}"));
        assertThrows(ResponseStatusException.class, () -> sources.get(b[0], b[1], source.id()));
        assertEquals(1, sources.sync(a[0], a[1], source.id()));
        assertEquals(1, sources.records(a[0], a[1], source.id(), 10).size());
        assertEquals(0, sources.sync(a[0], a[1], source.id()));
        assertTrue(sources.records(a[0], a[1], source.id(), 10).isEmpty());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM source_events WHERE connector_id=?", Integer.class, source.id()));
        assertThrows(ResponseStatusException.class, () -> sources.records(b[0], b[1], source.id(), 10));
    }

    @Test
    void rejectsResourcesOutsideServerCatalog() {
        UUID[] a = workspace();
        assertThrows(ResponseStatusException.class, () -> sources.connect(a[0], a[1],
                new ExternalSourceService.ConnectRequest("POSTGRESQL", "Unsafe", "not_configured", "{}")));
        verifyNoInteractions(reader);
    }

    @Test
    void multipleResourcesAndDisconnectKeepCredentialsAndTenantsSeparate() {
        UUID[] a = workspace(), b = workspace();
        String first = "view_a_" + UUID.randomUUID().toString().replace("-", "");
        String second = "view_b_" + UUID.randomUUID().toString().replace("-", "");
        for (String key : List.of(first, second)) {
            SourceCatalog.Resource resource = new SourceCatalog.Resource();
            resource.setType("POSTGRESQL");
            resource.setView("public.approved_view");
            resource.setIdColumn("id");
            resource.setJdbcUrl("jdbc:postgresql://example.invalid/sample");
            resource.setTenantId(a[1].toString());
            catalog.getResources().put(key, resource);
        }
        when(reader.read(eq("POSTGRESQL"), any(), anyString(), isNull(), eq(1)))
                .thenReturn(new SourceReader.Page(List.of(), null, "public.approved_view"));
        var one = sources.connect(a[0], a[1], new ExternalSourceService.ConnectRequest(
                "POSTGRESQL", "One", first, "{\"username\":\"reader\",\"password\":\"secret\"}"));
        sources.connect(a[0], a[1], new ExternalSourceService.ConnectRequest(
                "POSTGRESQL", "Two", second, "{\"username\":\"reader\",\"password\":\"secret\"}"));
        assertEquals(2, sources.list(a[0], a[1]).size());
        assertTrue(sources.available(b[0], b[1]).stream().noneMatch(resource -> resource.key().equals(first)));
        sources.disconnect(a[0], a[1], one.id());
        assertEquals("DISCONNECTED", sources.get(a[0], a[1], one.id()).status());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM connector_credentials WHERE connector_id=?", Integer.class, one.id()));
        assertThrows(ResponseStatusException.class, () -> sources.rotate(a[0], a[1], one.id(), "replacement"));
        assertThrows(ResponseStatusException.class, () -> sources.get(b[0], b[1], one.id()));
    }

    @Test
    void githubAuthorizationBindsInstallationToUserAndConsumesState() {
        UUID[] a = workspace();
        String key = "repo_" + UUID.randomUUID().toString().replace("-", "");
        SourceCatalog.Resource resource = new SourceCatalog.Resource();
        resource.setType("GITHUB"); resource.setRepository("example/approved");
        resource.setTenantId(a[1].toString());
        catalog.getResources().put(key, resource);
        when(reader.githubAuthorizeUrl(anyString())).thenAnswer(invocation ->
                "https://github.com/login/oauth/authorize?state=" + invocation.getArgument(0));
        String url = sources.beginGithub(a[0], a[1], "Repository", key, "12345");
        String state = url.substring(url.indexOf("state=") + 6);
        var connected = sources.finishGithub(state, "authorization-code");
        verify(reader).verifyGithubGrant("authorization-code", "12345", "example/approved");
        assertEquals("GITHUB", connected.type());
        assertThrows(ResponseStatusException.class, () -> sources.finishGithub(state, "authorization-code"));
        assertThrows(ResponseStatusException.class, () -> sources.connect(a[0], a[1],
                new ExternalSourceService.ConnectRequest("GITHUB", "Bypass", key, "12345")));
    }

    @Test
    void slackAuthorizationBindsChannelAndConsumesState() {
        UUID[] a = workspace();
        String key = "channel_" + UUID.randomUUID().toString().replace("-", "");
        SourceCatalog.Resource resource = new SourceCatalog.Resource();
        resource.setType("SLACK"); resource.setChannelId("C123456");
        resource.setTenantId(a[1].toString());
        catalog.getResources().put(key, resource);
        when(reader.slackAuthorizeUrl(anyString(), eq("C123456"))).thenAnswer(invocation ->
                "https://slack.com/oauth/v2/authorize?state=" + invocation.getArgument(0));
        when(reader.exchangeSlackCode("authorization-code")).thenReturn(
                new SourceReader.SlackTokens("bot-token", null, 0, "channels:read,channels:history"));
        String url = sources.beginSlack(a[0], a[1], "Channel", key);
        String state = url.substring(url.indexOf("state=") + 6);
        var connected = sources.finishSlack(state, "authorization-code");
        assertEquals("SLACK", connected.type());
        assertThrows(ResponseStatusException.class, () -> sources.finishSlack(state, "authorization-code"));
        sources.disconnect(a[0], a[1], connected.id());
        verify(reader).revokeSlack("bot-token");
    }

    @Test
    void interruptedSyncResumesFromCommittedPage() {
        UUID[] a = workspace();
        String key = "resume_" + UUID.randomUUID().toString().replace("-", "");
        SourceCatalog.Resource resource = new SourceCatalog.Resource();
        resource.setType("POSTGRESQL"); resource.setView("public.approved_view");
        resource.setIdColumn("id"); resource.setJdbcUrl("jdbc:postgresql://example.invalid/sample");
        catalog.getResources().put(key, resource);
        when(reader.read(eq("POSTGRESQL"), eq(resource), anyString(), isNull(), eq(1)))
                .thenReturn(new SourceReader.Page(List.of(), null, "public.approved_view"));
        var one = new SourceReader.Item("one", null, null, json.createObjectNode().put("id", "one"));
        var two = new SourceReader.Item("two", null, null, json.createObjectNode().put("id", "two"));
        when(reader.read(eq("POSTGRESQL"), eq(resource), anyString(), isNull(), eq(50)))
                .thenReturn(new SourceReader.Page(List.of(one), "next", "public.approved_view"));
        when(reader.read(eq("POSTGRESQL"), eq(resource), anyString(), eq("next"), eq(50)))
                .thenThrow(new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Source timed out"))
                .thenReturn(new SourceReader.Page(List.of(two), null, "public.approved_view"));
        var source = sources.connect(a[0], a[1], new ExternalSourceService.ConnectRequest(
                "POSTGRESQL", "Resume", key, "{\"username\":\"reader\",\"password\":\"secret\"}"));
        assertEquals(1, sources.sync(a[0], a[1], source.id()));
        assertEquals("ERROR", sources.get(a[0], a[1], source.id()).status());
        assertEquals("next", sources.get(a[0], a[1], source.id()).syncCursor());
        sources.test(a[0], a[1], source.id());
        assertEquals(1, sources.sync(a[0], a[1], source.id()));
        assertEquals(2, sources.records(a[0], a[1], source.id(), 10).size());
    }
}

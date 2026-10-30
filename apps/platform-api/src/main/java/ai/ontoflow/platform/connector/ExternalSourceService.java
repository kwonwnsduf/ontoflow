package ai.ontoflow.platform.connector;

import ai.ontoflow.platform.audit.service.AuditEventService;
import ai.ontoflow.platform.tenant.service.TenantAccessService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ExternalSourceService {
    public record ConnectRequest(String type, String name, String resourceKey, String credential) {}
    public record SourceView(UUID id, UUID tenantId, String type, String name, String resourceKey, String status,
                             String lastError, Instant lastCheckedAt, Instant lastSyncedAt, boolean readEnabled,
                             String syncStatus, String syncCursor, String allowedScope) {}
    public record RecordView(String resourceKey, String sourceId, String sourceUrl, Instant sourceUpdatedAt,
                             Instant collectedAt, String permissionScope, String checksum, boolean deleted, String payload) {}
    public record RegistryView(String type, String authType, String resourceType, String capability,
                               String inputSchema, String outputSchema) {}
    public record AvailableResource(String key, String type, String resourceType) {}
    private record Connection(UUID id, UUID tenant, String type, String name, String key, String cipher,
                              String status, String error, Instant checked, Instant synced) {}

    private final JdbcTemplate db;
    private final TenantAccessService access;
    private final AuditEventService audits;
    private final SecretStore secrets;
    private final SourceCatalog catalog;
    private final SourceReader reader;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private final SecureRandom random = new SecureRandom();

    public ExternalSourceService(JdbcTemplate db, TenantAccessService access, AuditEventService audits,
                                 SecretStore secrets, SourceCatalog catalog, SourceReader reader,
                                 PlatformTransactionManager manager, ObjectMapper json) {
        this.db = db; this.access = access; this.audits = audits;
        this.secrets = secrets; this.catalog = catalog; this.reader = reader;
        this.transactions = new TransactionTemplate(manager);
        this.json = json;
    }

    private Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Connection row(java.sql.ResultSet rs, int index) throws java.sql.SQLException {
        return new Connection(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("system_type"), rs.getString("name"), rs.getString("resource_key"),
                rs.getString("credential_ciphertext"), rs.getString("status"), rs.getString("last_error"),
                instant(rs, "last_checked_at"), instant(rs, "last_synced_at"));
    }

    private Connection require(UUID tenant, UUID id) {
        return db.query("SELECT * FROM connectors WHERE tenant_id=? AND id=? AND resource_key IS NOT NULL",
                this::row, tenant, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found"));
    }

    private boolean enabled(UUID id) {
        return Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM connector_capabilities WHERE connector_id=? AND action='source.read'",
                Boolean.class, id));
    }

    private SourceView view(Connection row) {
        var sync = db.query("SELECT status,cursor_value FROM source_sync_state WHERE connector_id=? AND resource_key=?",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, row.id(), row.key());
        SourceCatalog.Resource allowed = catalog.getResources().get(row.key());
        if (allowed != null && allowed.getTenantId() != null && !row.tenant().toString().equals(allowed.getTenantId()))
            allowed = null;
        String scope = allowed == null ? null : switch (row.type()) {
            case "POSTGRESQL", "MYSQL" -> allowed.getView();
            case "GITHUB" -> allowed.getRepository();
            case "SLACK" -> allowed.getChannelId();
            default -> null;
        };
        return new SourceView(row.id(), row.tenant(), row.type(), row.name(), row.key(), row.status(), row.error(),
                row.checked(), row.synced(), enabled(row.id()),
                sync.isEmpty() ? "NEVER" : sync.getFirst()[0], sync.isEmpty() ? null : sync.getFirst()[1], scope);
    }

    private SourceCatalog.Resource resource(UUID tenant, String type, String key) {
        SourceCatalog.Resource resource = catalog.getResources().get(key);
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,120}") || resource == null
                || !type.equalsIgnoreCase(resource.getType())
                || resource.getTenantId() != null && !tenant.toString().equals(resource.getTenantId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Resource is not allowed");
        return resource;
    }

    public List<RegistryView> registry(UUID user, UUID tenant) {
        access.requireMembership(user, tenant);
        return db.query("SELECT * FROM connector_registry ORDER BY system_type", (rs, i) ->
                new RegistryView(rs.getString("system_type"), rs.getString("auth_type"),
                        rs.getString("resource_type"), rs.getString("capability"),
                        rs.getString("input_schema"), rs.getString("output_schema")));
    }

    public List<AvailableResource> available(UUID user, UUID tenant) {
        access.requireMembership(user, tenant);
        return catalog.getResources().entrySet().stream()
                .filter(entry -> entry.getKey().matches("[A-Za-z0-9_-]{1,120}"))
                .filter(entry -> entry.getValue() != null && entry.getValue().getType() != null)
                .filter(entry -> entry.getValue().getTenantId() == null || tenant.toString().equals(entry.getValue().getTenantId()))
                .map(entry -> new AvailableResource(entry.getKey(), entry.getValue().getType(),
                        switch (entry.getValue().getType().toUpperCase(Locale.ROOT)) {
                            case "POSTGRESQL", "MYSQL" -> "VIEW";
                            case "GITHUB" -> "REPOSITORY";
                            case "SLACK" -> "CHANNEL";
                            default -> "UNKNOWN";
                        }))
                .toList();
    }

    public List<SourceView> list(UUID user, UUID tenant) {
        access.requireMembership(user, tenant);
        return db.query("SELECT * FROM connectors WHERE tenant_id=? AND resource_key IS NOT NULL ORDER BY created_at",
                this::row, tenant).stream().map(this::view).toList();
    }

    @Transactional
    public SourceView connect(UUID user, UUID tenant, ConnectRequest request) {
        access.requireAdministrator(user, tenant);
        if (request == null || request.type() == null || request.name() == null || request.credential() == null
                || request.name().isBlank() || request.name().length() > 100 || request.credential().isBlank()
                || request.credential().length() > 4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source connection");
        String type = request.type().toUpperCase(Locale.ROOT);
        if (!List.of("POSTGRESQL", "MYSQL").contains(type))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use provider authorization for this source");
        SourceCatalog.Resource resource = resource(tenant, type, request.resourceKey());
        reader.read(type, resource, request.credential(), null, 1);
        return save(tenant, user, type, request.name(), request.resourceKey(), request.credential());
    }

    private SourceView save(UUID tenant, UUID user, String type, String name, String key, String credential) {
        UUID id = UUID.randomUUID();
        UUID secretId = UUID.randomUUID();
        Instant now = Instant.now();
        db.update("INSERT INTO connectors (id,tenant_id,system_type,name,resource_key,credential_ciphertext,status,last_checked_at,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenant, type, name.strip(), key, "ref:" + secretId, "CONNECTED",
                Timestamp.from(now), Timestamp.from(now));
        db.update("INSERT INTO connector_credentials (id,connector_id,ciphertext,created_at) VALUES (?,?,?,?)",
                secretId, id, secrets.seal(credential), Timestamp.from(now));
        db.update("INSERT INTO connector_capabilities (connector_id,action,enabled) VALUES (?,?,true)", id, "source.read");
        audits.record(tenant, user, "SOURCE_CONNECT", id);
        return view(require(tenant, id));
    }

    public SourceView get(UUID user, UUID tenant, UUID id) {
        access.requireMembership(user, tenant);
        return view(require(tenant, id));
    }

    private String credential(Connection connection) {
        if (!connection.cipher().startsWith("ref:"))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source credential unavailable");
        UUID reference;
        try { reference = UUID.fromString(connection.cipher().substring(4)); }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source credential unavailable");
        }
        String ciphertext = db.query("SELECT ciphertext FROM connector_credentials WHERE id=? AND connector_id=?",
                (rs, i) -> rs.getString(1), reference, connection.id()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Source credential unavailable"));
        String plaintext = secrets.open(ciphertext);
        if (!"SLACK".equals(connection.type())) return plaintext;
        try {
            JsonNode tokens = json.readTree(plaintext);
            String accessToken = tokens.path("accessToken").asText();
            long expiresAt = tokens.path("expiresAt").asLong(0);
            if (accessToken.isBlank()) throw new IllegalArgumentException();
            if (expiresAt > 0 && expiresAt <= Instant.now().plusSeconds(60).getEpochSecond()) {
                SourceReader.SlackTokens refreshed = reader.refreshSlack(tokens.path("refreshToken").asText(null));
                db.update("UPDATE connector_credentials SET ciphertext=?,rotated_at=? WHERE id=? AND connector_id=?",
                        secrets.seal(json.writeValueAsString(refreshed)), Timestamp.from(Instant.now()), reference, connection.id());
                return refreshed.accessToken();
            }
            return accessToken;
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source credential unavailable");
        }
    }

    @Transactional
    public SourceView test(UUID user, UUID tenant, UUID id) {
        access.requireAdministrator(user, tenant);
        Connection connection = require(tenant, id);
        if ("DISCONNECTED".equals(connection.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source is disconnected");
        try {
            reader.read(connection.type(), resource(tenant, connection.type(), connection.key()),
                    credential(connection), null, 1);
            db.update("UPDATE connectors SET status='CONNECTED',last_error=NULL,last_checked_at=? WHERE id=? AND tenant_id=?",
                    Timestamp.from(Instant.now()), id, tenant);
        } catch (ResponseStatusException e) {
            db.update("UPDATE connectors SET status='ERROR',last_error=?,last_checked_at=? WHERE id=? AND tenant_id=?",
                    e.getReason(), Timestamp.from(Instant.now()), id, tenant);
            if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 404) conceal(tenant, connection);
        }
        audits.record(tenant, user, "SOURCE_TEST", id);
        return view(require(tenant, id));
    }

    @Transactional
    public SourceView capability(UUID user, UUID tenant, UUID id, boolean enabled) {
        access.requireAdministrator(user, tenant);
        Connection connection = require(tenant, id);
        if ("DISCONNECTED".equals(connection.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source is disconnected");
        db.update("UPDATE connector_capabilities SET enabled=? WHERE connector_id=? AND action='source.read'", enabled, id);
        if (!enabled) conceal(tenant, require(tenant, id));
        audits.record(tenant, user, "SOURCE_CAPABILITY_UPDATE", id);
        return view(require(tenant, id));
    }

    @Transactional
    public SourceView rotate(UUID user, UUID tenant, UUID id, String replacement) {
        access.requireAdministrator(user, tenant);
        Connection connection = require(tenant, id);
        if ("DISCONNECTED".equals(connection.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source is disconnected");
        if ("SLACK".equals(connection.type()) || "GITHUB".equals(connection.type()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reconnect using provider authorization");
        if (replacement == null || replacement.isBlank() || replacement.length() > 4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid credential");
        reader.read(connection.type(), resource(tenant, connection.type(), connection.key()), replacement, null, 1);
        db.update("UPDATE connector_credentials SET ciphertext=?,rotated_at=? WHERE connector_id=?",
                secrets.seal(replacement), Timestamp.from(Instant.now()), id);
        db.update("UPDATE connectors SET status='CONNECTED',last_error=NULL,last_checked_at=? WHERE tenant_id=? AND id=?",
                Timestamp.from(Instant.now()), tenant, id);
        audits.record(tenant, user, "SOURCE_CREDENTIAL_ROTATE", id);
        return view(require(tenant, id));
    }

    private Connection ready(UUID user, UUID tenant, UUID id) {
        access.requireMembership(user, tenant);
        Connection connection = require(tenant, id);
        if (!"CONNECTED".equals(connection.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source is not connected");
        resource(tenant, connection.type(), connection.key());
        if (!enabled(id)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Read capability disabled");
        return connection;
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public SourceReader.Page read(UUID user, UUID tenant, UUID id, String cursor, int limit) {
        Connection connection = ready(user, tenant, id);
        if (limit < 1 || limit > 100 || cursor != null && cursor.length() > 500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page request");
        SourceReader.Page page;
        try {
            page = reader.read(connection.type(), resource(tenant, connection.type(), connection.key()),
                    credential(connection), cursor, limit);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 404) {
                conceal(tenant, connection);
                db.update("UPDATE connectors SET status='ERROR',last_error=? WHERE tenant_id=? AND id=?",
                        e.getReason(), tenant, id);
            }
            audits.record(tenant, user, "SOURCE_READ_FAILED", id);
            throw e;
        }
        audits.record(tenant, user, "SOURCE_READ", id);
        return page;
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private void event(UUID tenant, UUID connector, String key, String sourceId, String type) {
        db.update("INSERT INTO source_events (id,tenant_id,connector_id,resource_key,source_id,event_type,occurred_at) VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, connector, key, sourceId, type, Timestamp.from(Instant.now()));
    }

    private void conceal(UUID tenant, Connection connection) {
        List<String> ids = db.query("SELECT source_id FROM source_records WHERE tenant_id=? AND connector_id=? AND deleted=false",
                (rs, i) -> rs.getString(1), tenant, connection.id());
        db.update("UPDATE source_records SET deleted=true,payload=NULL WHERE tenant_id=? AND connector_id=?",
                tenant, connection.id());
        for (String sourceId : ids) event(tenant, connection.id(), connection.key(), sourceId, "DELETE");
    }

    public int sync(UUID user, UUID tenant, UUID id) {
        access.requireAdministrator(user, tenant);
        Connection connection = ready(user, tenant, id);
        UUID lease = UUID.randomUUID();
        if (db.update("UPDATE connectors SET sync_lease_id=?,sync_lock_until=? WHERE tenant_id=? AND id=? AND (sync_lock_until IS NULL OR sync_lock_until<?)",
                lease, Timestamp.from(Instant.now().plusSeconds(900)), tenant, id, Timestamp.from(Instant.now())) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Source sync already running");
        try {
            return syncWithLease(user, tenant, connection, lease);
        } finally {
            db.update("UPDATE connectors SET sync_lease_id=NULL,sync_lock_until=NULL WHERE tenant_id=? AND id=? AND sync_lease_id=?",
                    tenant, id, lease);
        }
    }

    private int syncWithLease(UUID user, UUID tenant, Connection connection, UUID lease) {
        UUID id = connection.id();
        var checkpoints = db.query("SELECT cursor_value,scan_started_at FROM source_sync_state WHERE connector_id=? AND resource_key=? AND status='PARTIAL'",
                (rs, i) -> new Object[]{rs.getString(1), rs.getTimestamp(2).toInstant()}, id, connection.key());
        String cursor = checkpoints.isEmpty() ? null : (String) checkpoints.getFirst()[0];
        Instant started = checkpoints.isEmpty()
                ? Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
                : (Instant) checkpoints.getFirst()[1];
        int count = 0;
        try {
            for (int pageNumber = 0; pageNumber < 20; pageNumber++) {
                SourceReader.Page page = reader.read(connection.type(), resource(tenant, connection.type(), connection.key()),
                        credential(connection), cursor, 50);
                if (page.nextCursor() != null && page.nextCursor().length() > 500)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source cursor exceeds limit");
                if (page.nextCursor() != null && page.nextCursor().equals(cursor))
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source cursor did not advance");
                Instant scanStarted = started;
                transactions.executeWithoutResult(status -> savePage(tenant, connection, page, scanStarted));
                db.update("UPDATE connectors SET sync_lock_until=? WHERE tenant_id=? AND id=? AND sync_lease_id=?",
                        Timestamp.from(Instant.now().plusSeconds(900)), tenant, id, lease);
                count += page.items().size();
                cursor = page.nextCursor();
                if (cursor == null) break;
            }
            transactions.executeWithoutResult(status -> {
                db.update("UPDATE connectors SET last_synced_at=?,status='CONNECTED',last_error=NULL WHERE tenant_id=? AND id=?",
                        Timestamp.from(Instant.now()), tenant, id);
                audits.record(tenant, user, "SOURCE_SYNC", id);
            });
            return count;
        } catch (ResponseStatusException e) {
            transactions.executeWithoutResult(status -> {
                db.update("UPDATE connectors SET status='ERROR',last_error=? WHERE tenant_id=? AND id=?", e.getReason(), tenant, id);
                if (e.getStatusCode().value() == 403 || e.getStatusCode().value() == 404) conceal(tenant, connection);
                audits.record(tenant, user, "SOURCE_SYNC_FAILED", id);
            });
            return count;
        }
    }

    private void savePage(UUID tenant, Connection connection, SourceReader.Page page, Instant started) {
        UUID id = connection.id();
        for (SourceReader.Item item : page.items()) {
            if (item.id() == null || item.id().isBlank()) continue;
            if (item.id().length() > 300 || item.url() != null && item.url().length() > 1000
                    || page.scope() == null || page.scope().length() > 300)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source metadata exceeds limits");
            String payload = item.body().toString();
            if (payload.length() > 1_000_000)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source record exceeds size limit");
            String checksum = hash(payload);
            Instant updated = null;
            try { if (item.updatedAt() != null) updated = Instant.parse(item.updatedAt()); }
            catch (Exception ignored) { /* source time remains unknown */ }
            var prior = db.query("SELECT checksum,deleted FROM source_records WHERE tenant_id=? AND connector_id=? AND resource_key=? AND source_id=?",
                    (rs, i) -> new Object[]{rs.getString(1), rs.getBoolean(2)}, tenant, id, connection.key(), item.id());
            int changed = db.update("UPDATE source_records SET source_url=?,source_updated_at=?,collected_at=?,permission_scope=?,checksum=?,deleted=false,payload=? WHERE tenant_id=? AND connector_id=? AND resource_key=? AND source_id=?",
                    item.url(), updated == null ? null : Timestamp.from(updated), Timestamp.from(started),
                    page.scope(), checksum, payload, tenant, id, connection.key(), item.id());
            if (changed == 0) db.update("INSERT INTO source_records (id,tenant_id,connector_id,resource_key,source_id,source_url,source_updated_at,collected_at,permission_scope,checksum,deleted,payload) VALUES (?,?,?,?,?,?,?,?,?,?,false,?)",
                    UUID.randomUUID(), tenant, id, connection.key(), item.id(), item.url(),
                    updated == null ? null : Timestamp.from(updated), Timestamp.from(started), page.scope(), checksum, payload);
            if (prior.isEmpty() || (boolean) prior.getFirst()[1] || !checksum.equals(prior.getFirst()[0]))
                event(tenant, id, connection.key(), item.id(), prior.isEmpty() ? "CREATE" : "UPDATE");
        }
        db.update("DELETE FROM source_sync_state WHERE connector_id=? AND resource_key=?", id, connection.key());
        db.update("INSERT INTO source_sync_state (connector_id,resource_key,cursor_value,scan_started_at,status,updated_at) VALUES (?,?,?,?,?,?)",
                id, connection.key(), page.nextCursor(), Timestamp.from(started),
                page.nextCursor() == null ? "COMPLETE" : "PARTIAL", Timestamp.from(Instant.now()));
        if (page.nextCursor() == null) {
            List<String> missing = db.query("SELECT source_id FROM source_records WHERE tenant_id=? AND connector_id=? AND resource_key=? AND deleted=false AND collected_at<?",
                    (rs, i) -> rs.getString(1), tenant, id, connection.key(), Timestamp.from(started));
            db.update("UPDATE source_records SET deleted=true,payload=NULL WHERE tenant_id=? AND connector_id=? AND resource_key=? AND collected_at<?",
                    tenant, id, connection.key(), Timestamp.from(started));
            for (String sourceId : missing) event(tenant, id, connection.key(), sourceId, "DELETE");
        }
    }

    @Transactional
    public List<RecordView> records(UUID user, UUID tenant, UUID id, int limit) {
        ready(user, tenant, id);
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid limit");
        List<RecordView> result = db.query("SELECT * FROM source_records WHERE tenant_id=? AND connector_id=? AND deleted=false ORDER BY source_id LIMIT ?",
                (rs, i) -> new RecordView(rs.getString("resource_key"), rs.getString("source_id"),
                        rs.getString("source_url"), instant(rs, "source_updated_at"), instant(rs, "collected_at"),
                        rs.getString("permission_scope"), rs.getString("checksum"), rs.getBoolean("deleted"), rs.getString("payload")),
                tenant, id, limit);
        audits.record(tenant, user, "SOURCE_RECORDS_READ", id);
        return result;
    }

    @Transactional
    public List<RecordView> provenance(UUID user, UUID tenant, UUID id, int limit) {
        access.requireMembership(user, tenant);
        Connection connection = require(tenant, id);
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid limit");
        boolean expose = "CONNECTED".equals(connection.status()) && enabled(id);
        try { resource(tenant, connection.type(), connection.key()); }
        catch (ResponseStatusException ignored) { expose = false; }
        final boolean canExpose = expose;
        List<RecordView> result = db.query("SELECT * FROM source_records WHERE tenant_id=? AND connector_id=? ORDER BY source_id LIMIT ?",
                (rs, i) -> new RecordView(rs.getString("resource_key"), rs.getString("source_id"),
                        rs.getString("source_url"), instant(rs, "source_updated_at"), instant(rs, "collected_at"),
                        rs.getString("permission_scope"), rs.getString("checksum"), rs.getBoolean("deleted"),
                        canExpose && !rs.getBoolean("deleted") ? rs.getString("payload") : null),
                tenant, id, limit);
        audits.record(tenant, user, "SOURCE_PROVENANCE_READ", id);
        return result;
    }

    @Transactional
    public void disconnect(UUID user, UUID tenant, UUID id) {
        access.requireAdministrator(user, tenant);
        Connection connection = require(tenant, id);
        if ("SLACK".equals(connection.type()) && "CONNECTED".equals(connection.status())) {
            try { reader.revokeSlack(credential(connection)); }
            catch (ResponseStatusException ignored) { /* local access still must be removed */ }
        }
        conceal(tenant, connection);
        db.update("DELETE FROM connector_credentials WHERE connector_id=?", id);
        db.update("UPDATE connectors SET status='DISCONNECTED',credential_ciphertext=?,last_error=NULL WHERE tenant_id=? AND id=?",
                "revoked", tenant, id);
        db.update("UPDATE connector_capabilities SET enabled=false WHERE connector_id=?", id);
        audits.record(tenant, user, "SOURCE_DISCONNECT", id);
    }

    @Transactional
    public String beginSlack(UUID user, UUID tenant, String name, String key) {
        access.requireAdministrator(user, tenant);
        SourceCatalog.Resource resource = resource(tenant, "SLACK", key);
        if (name == null || name.isBlank() || name.length() > 100)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid name");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO source_oauth_states (state_hash,provider,tenant_id,actor_id,connector_name,resource_key,expires_at) VALUES (?,?,?,?,?,?,?)",
                hash(state), "SLACK", tenant, user, name, key, Timestamp.from(Instant.now().plusSeconds(600)));
        return reader.slackAuthorizeUrl(state, resource.getChannelId());
    }

    @Transactional
    public SourceView finishSlack(String state, String code) {
        if (state == null || code == null || state.length() > 200 || code.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth callback");
        var states = db.query("SELECT tenant_id,actor_id,connector_name,resource_key,expires_at FROM source_oauth_states WHERE state_hash=? AND provider='SLACK'",
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant()}, hash(state));
        if (states.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state");
        Object[] entry = states.getFirst();
        if (db.update("DELETE FROM source_oauth_states WHERE state_hash=?", hash(state)) != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state already used");
        if (((Instant) entry[4]).isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state expired");
        UUID tenant = (UUID) entry[0], user = (UUID) entry[1];
        access.requireAdministrator(user, tenant);
        SourceReader.SlackTokens tokens = reader.exchangeSlackCode(code);
        reader.read("SLACK", resource(tenant, "SLACK", (String) entry[3]), tokens.accessToken(), null, 1);
        try {
            return save(tenant, user, "SLACK", (String) entry[2], (String) entry[3], json.writeValueAsString(tokens));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store source authorization");
        }
    }

    public String githubInstallUrl(UUID user, UUID tenant) {
        access.requireAdministrator(user, tenant);
        return reader.githubInstallUrl();
    }

    @Transactional
    public String beginGithub(UUID user, UUID tenant, String name, String key, String installation) {
        access.requireAdministrator(user, tenant);
        resource(tenant, "GITHUB", key);
        if (name == null || name.isBlank() || name.length() > 100
                || installation == null || !installation.matches("[1-9][0-9]{0,18}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GitHub installation");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("INSERT INTO source_oauth_states (state_hash,provider,tenant_id,actor_id,connector_name,resource_key,installation_id,expires_at) VALUES (?,?,?,?,?,?,?,?)",
                hash(state), "GITHUB", tenant, user, name, key, installation, Timestamp.from(Instant.now().plusSeconds(600)));
        return reader.githubAuthorizeUrl(state);
    }

    @Transactional
    public SourceView finishGithub(String state, String code) {
        if (state == null || code == null || state.length() > 200 || code.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth callback");
        var states = db.query("SELECT tenant_id,actor_id,connector_name,resource_key,installation_id,expires_at FROM source_oauth_states WHERE state_hash=? AND provider='GITHUB'",
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5), rs.getTimestamp(6).toInstant()}, hash(state));
        if (states.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state");
        Object[] entry = states.getFirst();
        if (db.update("DELETE FROM source_oauth_states WHERE state_hash=?", hash(state)) != 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state already used");
        if (((Instant) entry[5]).isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state expired");
        UUID tenant = (UUID) entry[0], user = (UUID) entry[1];
        access.requireAdministrator(user, tenant);
        SourceCatalog.Resource resource = resource(tenant, "GITHUB", (String) entry[3]);
        reader.verifyGithubGrant(code, (String) entry[4], resource.getRepository());
        reader.read("GITHUB", resource, (String) entry[4], null, 1);
        return save(tenant, user, "GITHUB", (String) entry[2], (String) entry[3], (String) entry[4]);
    }
}

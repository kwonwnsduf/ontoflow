package ai.ontoflow.platform.connector;

import ai.ontoflow.platform.audit.service.AuditEventService;
import ai.ontoflow.platform.tenant.service.TenantAccessService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ConnectorService {
    public static final List<String> ACTIONS = List.of("payment.list_failed", "payment.get_detail",
            "crm.find_customer", "crm.list_employees", "crm.create_followup", "email.create_draft");
    private final JdbcTemplate db;
    private final TenantAccessService access;
    private final SecretStore secrets;
    private final MockEnterpriseAdapter adapters;
    private final AuditEventService audits;

    public ConnectorService(JdbcTemplate db, TenantAccessService access, SecretStore secrets,
                            MockEnterpriseAdapter adapters, AuditEventService audits) {
        this.db = db;
        this.access = access;
        this.secrets = secrets;
        this.adapters = adapters;
        this.audits = audits;
    }

    public record CreateRequest(@NotBlank String systemType, @NotBlank @Size(max = 100) String name,
                                @NotBlank String credential) {}
    public record ConnectorView(UUID id, String systemType, String name, String status,
                                String lastError, Instant lastCheckedAt, List<Capability> capabilities) {}
    public record Capability(String action, boolean enabled) {}
    public record FollowupRequest(@NotBlank String customerId, @NotBlank String assigneeId,
                                  @NotBlank @Size(max = 500) String summary) {}
    public record DraftRequest(@NotBlank String to, @NotBlank String subject,
                               @NotBlank String content) {}
    private record Row(UUID id, UUID tenantId, String type, String name, String cipher,
                       String status, String error, Instant checkedAt) {}

    private Row row(ResultSet rs, int index) throws SQLException {
        return new Row(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("system_type"), rs.getString("name"), rs.getString("credential_ciphertext"),
                rs.getString("status"), rs.getString("last_error"),
                rs.getTimestamp("last_checked_at") == null ? null : rs.getTimestamp("last_checked_at").toInstant());
    }

    private Row require(UUID tenant, UUID id) {
        return db.query("SELECT * FROM connectors WHERE tenant_id=? AND id=?", this::row, tenant, id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found"));
    }

    private ConnectorView view(Row row) {
        return new ConnectorView(row.id(), row.type(), row.name(), row.status(), row.error(),
                row.checkedAt(), capabilities(row.id()));
    }

    private List<Capability> capabilities(UUID id) {
        return db.query("SELECT action, enabled FROM connector_capabilities WHERE connector_id=? ORDER BY action",
                (rs, index) -> new Capability(rs.getString(1), rs.getBoolean(2)), id);
    }

    public List<ConnectorView> list(UUID user, UUID tenant) {
        access.requireMembership(user, tenant);
        return db.query("SELECT * FROM connectors WHERE tenant_id=? ORDER BY created_at, id", this::row, tenant)
                .stream().map(this::view).toList();
    }

    @Transactional
    public ConnectorView create(UUID user, UUID tenant, CreateRequest request) {
        access.requireAdministrator(user, tenant);
        String type = request.systemType().toUpperCase(java.util.Locale.ROOT);
        EnterpriseAdapter adapter = adapters.forType(type);
        if (request.credential().length() > 512 || request.credential().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid credential");
        // Reject bad credentials before persisting any connection.
        adapter.test(request.credential());
        UUID id = UUID.randomUUID();
        db.update("INSERT INTO connectors (id,tenant_id,system_type,name,credential_ciphertext,status,last_checked_at,created_at) VALUES (?,?,?,?,?,?,?,?)",
                id, tenant, type, request.name().strip(), secrets.seal(request.credential()),
                "CONNECTED", java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));
        for (String action : ACTIONS)
            db.update("INSERT INTO connector_capabilities (connector_id,action,enabled) VALUES (?,?,true)", id, action);
        audits.record(tenant, user, "CONNECTOR_CREATE", id);
        return view(require(tenant, id));
    }

    public ConnectorView get(UUID user, UUID tenant, UUID id) {
        access.requireMembership(user, tenant);
        return view(require(tenant, id));
    }

    public ConnectorView test(UUID user, UUID tenant, UUID id) {
        access.requireAdministrator(user, tenant);
        Row row = require(tenant, id);
        try {
            adapters.forType(row.type()).test(secrets.open(row.cipher()));
            db.update("UPDATE connectors SET status='CONNECTED',last_error=NULL,last_checked_at=? WHERE id=? AND tenant_id=?",
                    java.sql.Timestamp.from(Instant.now()), id, tenant);
        } catch (ResponseStatusException e) {
            db.update("UPDATE connectors SET status='ERROR',last_error=?,last_checked_at=? WHERE id=? AND tenant_id=?",
                    e.getReason(), java.sql.Timestamp.from(Instant.now()), id, tenant);
        }
        return view(require(tenant, id));
    }

    @Transactional
    public ConnectorView capability(UUID user, UUID tenant, UUID id, String action, boolean enabled) {
        access.requireAdministrator(user, tenant);
        require(tenant, id);
        if (!ACTIONS.contains(action)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capability not found");
        db.update("UPDATE connector_capabilities SET enabled=? WHERE connector_id=? AND action=?", enabled, id, action);
        audits.record(tenant, user, "CONNECTOR_CAPABILITY_UPDATE", id);
        return view(require(tenant, id));
    }

    @Transactional
    public void delete(UUID user, UUID tenant, UUID id) {
        access.requireAdministrator(user, tenant);
        require(tenant, id);
        db.update("DELETE FROM connectors WHERE tenant_id=? AND id=?", tenant, id);
        audits.record(tenant, user, "CONNECTOR_DELETE", id);
    }

    private EnterpriseAdapter ready(UUID user, UUID tenant, UUID id, String action) {
        access.requireMembership(user, tenant);
        Row row = require(tenant, id);
        if (!"CONNECTED".equals(row.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connector is not connected");
        boolean enabled = capabilities(id).stream().anyMatch(c -> c.action().equals(action) && c.enabled());
        if (!enabled) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Capability disabled");
        return adapters.forType(row.type());
    }

    private String credential(UUID tenant, UUID id) { return secrets.open(require(tenant, id).cipher()); }
    public List<EnterpriseAdapter.Payment> failedPayments(UUID user, UUID tenant, UUID id) {
        return ready(user, tenant, id, "payment.list_failed").failedPayments(credential(tenant, id));
    }
    public EnterpriseAdapter.Payment payment(UUID user, UUID tenant, UUID id, String payment) {
        return ready(user, tenant, id, "payment.get_detail").payment(credential(tenant, id), payment);
    }
    public EnterpriseAdapter.Customer customer(UUID user, UUID tenant, UUID id, String customer) {
        return ready(user, tenant, id, "crm.find_customer").customer(credential(tenant, id), customer);
    }
    public List<EnterpriseAdapter.Employee> employees(UUID user, UUID tenant, UUID id) {
        return ready(user, tenant, id, "crm.list_employees").employees(credential(tenant, id));
    }
    @Transactional
    public EnterpriseAdapter.Followup followup(UUID user, UUID tenant, UUID id, FollowupRequest request) {
        access.requireAdministrator(user, tenant);
        EnterpriseAdapter adapter = ready(user, tenant, id, "crm.create_followup");
        EnterpriseAdapter.Followup result = adapter.createFollowup(credential(tenant, id),
                request.customerId(), request.assigneeId(), request.summary());
        audits.record(tenant, user, "CRM_FOLLOWUP_CREATE", id);
        return result;
    }
    @Transactional
    public EnterpriseAdapter.Draft draft(UUID user, UUID tenant, UUID id, DraftRequest request) {
        access.requireAdministrator(user, tenant);
        EnterpriseAdapter adapter = ready(user, tenant, id, "email.create_draft");
        EnterpriseAdapter.Draft result = adapter.createDraft(credential(tenant, id),
                request.to(), request.subject(), request.content());
        audits.record(tenant, user, "EMAIL_DRAFT_CREATE", id);
        return result;
    }
}

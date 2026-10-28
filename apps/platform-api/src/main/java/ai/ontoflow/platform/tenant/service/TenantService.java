package ai.ontoflow.platform.tenant.service;

import ai.ontoflow.platform.audit.service.AuditEventService;
import ai.ontoflow.platform.common.dto.PageRequest;
import ai.ontoflow.platform.common.dto.PageResponse;
import ai.ontoflow.platform.tenant.domain.Role;
import ai.ontoflow.platform.tenant.domain.Tenant;
import ai.ontoflow.platform.tenant.domain.TenantContext;
import ai.ontoflow.platform.tenant.domain.TenantMember;
import ai.ontoflow.platform.tenant.dto.CreateTenantRequest;
import ai.ontoflow.platform.tenant.dto.WorkspaceResponse;
import ai.ontoflow.platform.tenant.repository.TenantMemberRepository;
import ai.ontoflow.platform.tenant.repository.TenantRepository;
import ai.ontoflow.platform.user.service.UserService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenantService {
    private final UserService users;
    private final TenantRepository tenants;
    private final TenantMemberRepository members;
    private final TenantAccessService access;
    private final AuditEventService auditEvents;

    public TenantService(
            UserService users,
            TenantRepository tenants,
            TenantMemberRepository members,
            TenantAccessService access,
            AuditEventService auditEvents) {
        this.users = users;
        this.tenants = tenants;
        this.members = members;
        this.access = access;
        this.auditEvents = auditEvents;
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkspaceResponse> list(UUID userId, PageRequest page) {
        users.requireActive(userId);
        List<WorkspaceResponse> items =
                members.findByUserIdOrderByCreatedAtAscIdAsc(userId, page.toPageable()).stream()
                        .map(
                                member ->
                                        workspace(
                                                tenants.findById(member.getTenantId())
                                                        .orElseThrow(),
                                                member.getRole()))
                        .toList();

        return new PageResponse<>(items, page.page(), page.size(), members.countByUserId(userId));
    }

    public WorkspaceResponse create(UUID userId, CreateTenantRequest request) {
        users.requireActive(userId);
        Instant now = Instant.now();
        Tenant tenant =
                new Tenant(
                        UUID.randomUUID(),
                        request.name().strip(),
                        request.slug(),
                        "ACTIVE",
                        now,
                        now);
        tenants.save(tenant);
        members.save(new TenantMember(UUID.randomUUID(), tenant.getId(), userId, Role.OWNER, now));
        auditEvents.record(tenant.getId(), userId, "TENANT_CREATE", tenant.getId());
        return workspace(tenant, Role.OWNER);
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse get(UUID userId, UUID tenantId) {
        TenantContext context = access.requireMembership(userId, tenantId);
        return workspace(tenants.findById(tenantId).orElseThrow(), context.role());
    }

    public WorkspaceResponse switchWorkspace(UUID userId, UUID tenantId) {
        WorkspaceResponse workspace = get(userId, tenantId);
        auditEvents.record(tenantId, userId, "TENANT_SWITCH", tenantId);
        return workspace;
    }

    private WorkspaceResponse workspace(Tenant tenant, Role role) {
        return new WorkspaceResponse(tenant.getId(), tenant.getName(), tenant.getSlug(), role);
    }
}

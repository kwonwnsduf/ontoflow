package ai.ontoflow.platform.tenant.service;

import ai.ontoflow.platform.tenant.domain.Role;
import ai.ontoflow.platform.tenant.domain.Tenant;
import ai.ontoflow.platform.tenant.domain.TenantContext;
import ai.ontoflow.platform.tenant.domain.TenantMember;
import ai.ontoflow.platform.tenant.repository.TenantMemberRepository;
import ai.ontoflow.platform.tenant.repository.TenantRepository;
import ai.ontoflow.platform.user.service.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class TenantAccessService {
    private final UserService users;
    private final TenantMemberRepository members;
    private final TenantRepository tenants;

    public TenantAccessService(
            UserService users, TenantMemberRepository members, TenantRepository tenants) {
        this.users = users;
        this.members = members;
        this.tenants = tenants;
    }

    public TenantContext requireMembership(UUID userId, UUID tenantId) {
        users.requireActive(userId);
        TenantMember member =
                members.findByTenantIdAndUserId(tenantId, userId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.FORBIDDEN, "Workspace access denied"));
        Tenant tenant = tenants.findById(tenantId).orElse(null);

        if (tenant == null || !"ACTIVE".equals(tenant.getStatus())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace unavailable");
        }
        return new TenantContext(tenantId, userId, member.getRole());
    }

    public TenantContext requireAdministrator(UUID userId, UUID tenantId) {
        TenantContext context = requireMembership(userId, tenantId);
        if (context.role() != Role.OWNER && context.role() != Role.ADMIN) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Workspace administrator required");
        }
        return context;
    }

    public void requireRoleManagement(TenantContext context, Role role) {
        if (context.role() != Role.OWNER && (role == Role.OWNER || role == Role.ADMIN)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Only owners can manage administrator roles");
        }
    }
}

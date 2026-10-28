package ai.ontoflow.platform.tenant.service;

import ai.ontoflow.platform.audit.service.AuditEventService;
import ai.ontoflow.platform.common.dto.PageRequest;
import ai.ontoflow.platform.common.dto.PageResponse;
import ai.ontoflow.platform.tenant.domain.Role;
import ai.ontoflow.platform.tenant.domain.TenantContext;
import ai.ontoflow.platform.tenant.domain.TenantMember;
import ai.ontoflow.platform.tenant.dto.AddMemberRequest;
import ai.ontoflow.platform.tenant.dto.MemberResponse;
import ai.ontoflow.platform.tenant.repository.TenantMemberRepository;
import ai.ontoflow.platform.tenant.repository.TenantRepository;
import ai.ontoflow.platform.user.domain.User;
import ai.ontoflow.platform.user.repository.UserRepository;
import ai.ontoflow.platform.user.service.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TenantMemberService {
    private final TenantRepository tenants;
    private final TenantMemberRepository members;
    private final UserRepository users;
    private final UserService userService;
    private final TenantAccessService access;
    private final AuditEventService auditEvents;

    public TenantMemberService(
            TenantRepository tenants,
            TenantMemberRepository members,
            UserRepository users,
            UserService userService,
            TenantAccessService access,
            AuditEventService auditEvents) {
        this.tenants = tenants;
        this.members = members;
        this.users = users;
        this.userService = userService;
        this.access = access;
        this.auditEvents = auditEvents;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(UUID userId, UUID tenantId, PageRequest page) {
        access.requireAdministrator(userId, tenantId);
        List<MemberResponse> items =
                members.findByTenantIdOrderByCreatedAtAscIdAsc(tenantId, page.toPageable()).stream()
                        .map(this::response)
                        .toList();

        return new PageResponse<>(
                items, page.page(), page.size(), members.countByTenantId(tenantId));
    }

    public MemberResponse add(UUID userId, UUID tenantId, AddMemberRequest request) {
        tenants.findByIdForUpdate(tenantId);
        TenantContext context = access.requireAdministrator(userId, tenantId);
        access.requireRoleManagement(context, request.role());

        User target =
                users.findByEmail(UserService.normalizeEmail(request.email()))
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Registered user not found"));
        TenantMember member =
                new TenantMember(
                        UUID.randomUUID(), tenantId, target.getId(), request.role(), Instant.now());
        members.save(member);
        auditEvents.record(tenantId, userId, "MEMBER_ADD", member.getId());
        return response(member);
    }

    public void changeRole(UUID userId, UUID tenantId, UUID memberId, Role role) {
        tenants.findByIdForUpdate(tenantId);
        TenantContext context = access.requireAdministrator(userId, tenantId);
        TenantMember member = requireMember(tenantId, memberId);

        access.requireRoleManagement(context, member.getRole());
        access.requireRoleManagement(context, role);
        if (role != Role.OWNER) {
            protectLastOwner(tenantId, member);
        }

        member.changeRole(role);
        auditEvents.record(tenantId, userId, "MEMBER_ROLE_CHANGE", memberId);
    }

    public void remove(UUID userId, UUID tenantId, UUID memberId) {
        tenants.findByIdForUpdate(tenantId);
        TenantContext context = access.requireAdministrator(userId, tenantId);
        TenantMember member = requireMember(tenantId, memberId);

        access.requireRoleManagement(context, member.getRole());
        protectLastOwner(tenantId, member);
        members.delete(member);
        auditEvents.record(tenantId, userId, "MEMBER_REMOVE", memberId);
    }

    private TenantMember requireMember(UUID tenantId, UUID memberId) {
        return members.findById(memberId)
                .filter(member -> member.getTenantId().equals(tenantId))
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Member not found"));
    }

    private void protectLastOwner(UUID tenantId, TenantMember member) {
        if (member.getRole() == Role.OWNER
                && members.countByTenantIdAndRole(tenantId, Role.OWNER) <= 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Workspace must retain an owner");
        }
    }

    private MemberResponse response(TenantMember member) {
        User user = userService.requireActive(member.getUserId());
        return new MemberResponse(
                member.getId(),
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                member.getRole());
    }
}

package ai.ontoflow.platform.audit.service;

import ai.ontoflow.platform.audit.dto.AuditResponse;
import ai.ontoflow.platform.audit.repository.AuditLogRepository;
import ai.ontoflow.platform.common.dto.PageRequest;
import ai.ontoflow.platform.common.dto.PageResponse;
import ai.ontoflow.platform.tenant.service.TenantAccessService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuditQueryService {
    private final AuditLogRepository audits;
    private final TenantAccessService access;

    public AuditQueryService(AuditLogRepository audits, TenantAccessService access) {
        this.audits = audits;
        this.access = access;
    }

    public PageResponse<AuditResponse> list(UUID userId, UUID tenantId, PageRequest page) {
        access.requireAdministrator(userId, tenantId);
        List<AuditResponse> items =
                audits.findByTenantIdOrderByCreatedAtDescIdAsc(tenantId, page.toPageable()).stream()
                        .map(
                                audit ->
                                        new AuditResponse(
                                                audit.getId(),
                                                audit.getTenantId(),
                                                audit.getUserId(),
                                                audit.getAction(),
                                                audit.getTargetId(),
                                                audit.getTraceId(),
                                                audit.getCreatedAt()))
                        .toList();

        return new PageResponse<>(
                items, page.page(), page.size(), audits.countByTenantId(tenantId));
    }
}

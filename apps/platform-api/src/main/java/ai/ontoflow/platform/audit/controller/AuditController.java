package ai.ontoflow.platform.audit.controller;

import ai.ontoflow.platform.audit.dto.AuditResponse;
import ai.ontoflow.platform.audit.service.AuditQueryService;
import ai.ontoflow.platform.common.dto.PageRequest;
import ai.ontoflow.platform.common.dto.PageResponse;
import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.CurrentUser;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/audit-logs")
public class AuditController {
    private final AuditQueryService audits;

    public AuditController(AuditQueryService audits) {
        this.audits = audits;
    }

    @GetMapping
    public PageResponse<AuditResponse> list(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @PathVariable UUID tenant,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return audits.list(CurrentUser.id(jwt), tenant, new PageRequest(page, size));
    }
}

package ai.ontoflow.platform.tenant.controller;

import ai.ontoflow.platform.common.dto.PageRequest;
import ai.ontoflow.platform.common.dto.PageResponse;
import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.CurrentUser;
import ai.ontoflow.platform.tenant.dto.CreateTenantRequest;
import ai.ontoflow.platform.tenant.dto.WorkspaceResponse;
import ai.ontoflow.platform.tenant.service.TenantService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {
    private final TenantService tenants;

    public TenantController(TenantService tenants) {
        this.tenants = tenants;
    }

    @GetMapping
    public PageResponse<WorkspaceResponse> list(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return tenants.list(CurrentUser.id(jwt), new PageRequest(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceResponse create(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @Valid @RequestBody CreateTenantRequest request) {
        return tenants.create(CurrentUser.id(jwt), request);
    }

    @GetMapping("/{tenant}")
    public WorkspaceResponse get(
            @AuthenticationPrincipal AuthPrincipal jwt, @PathVariable UUID tenant) {
        return tenants.get(CurrentUser.id(jwt), tenant);
    }

    @PostMapping("/{tenant}/switch")
    public WorkspaceResponse switchWorkspace(
            @AuthenticationPrincipal AuthPrincipal jwt, @PathVariable UUID tenant) {
        return tenants.switchWorkspace(CurrentUser.id(jwt), tenant);
    }
}

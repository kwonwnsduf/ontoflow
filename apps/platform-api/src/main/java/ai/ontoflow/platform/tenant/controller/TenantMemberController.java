package ai.ontoflow.platform.tenant.controller;

import ai.ontoflow.platform.common.dto.PageRequest;
import ai.ontoflow.platform.common.dto.PageResponse;
import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.CurrentUser;
import ai.ontoflow.platform.tenant.dto.AddMemberRequest;
import ai.ontoflow.platform.tenant.dto.ChangeRoleRequest;
import ai.ontoflow.platform.tenant.dto.MemberResponse;
import ai.ontoflow.platform.tenant.service.TenantMemberService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/members")
public class TenantMemberController {
    private final TenantMemberService members;

    public TenantMemberController(TenantMemberService members) {
        this.members = members;
    }

    @GetMapping
    public PageResponse<MemberResponse> list(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @PathVariable UUID tenant,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return members.list(CurrentUser.id(jwt), tenant, new PageRequest(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemberResponse add(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @PathVariable UUID tenant,
            @Valid @RequestBody AddMemberRequest request) {
        return members.add(CurrentUser.id(jwt), tenant, request);
    }

    @PatchMapping("/{member}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changeRole(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @PathVariable UUID tenant,
            @PathVariable UUID member,
            @Valid @RequestBody ChangeRoleRequest request) {
        members.changeRole(CurrentUser.id(jwt), tenant, member, request.role());
    }

    @DeleteMapping("/{member}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @AuthenticationPrincipal AuthPrincipal jwt,
            @PathVariable UUID tenant,
            @PathVariable UUID member) {
        members.remove(CurrentUser.id(jwt), tenant, member);
    }
}

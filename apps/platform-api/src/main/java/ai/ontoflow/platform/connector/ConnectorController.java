package ai.ontoflow.platform.connector;

import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/connectors")
public class ConnectorController {
    private final ConnectorService service;
    public ConnectorController(ConnectorService service) { this.service = service; }

    @GetMapping
    public List<ConnectorService.ConnectorView> list(@AuthenticationPrincipal AuthPrincipal user,
                                                      @PathVariable UUID tenant) {
        return service.list(CurrentUser.id(user), tenant);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectorService.ConnectorView create(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @Valid @RequestBody ConnectorService.CreateRequest request) {
        return service.create(CurrentUser.id(user), tenant, request);
    }

    @GetMapping("/{id}")
    public ConnectorService.ConnectorView get(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        return service.get(CurrentUser.id(user), tenant, id);
    }

    @PostMapping("/{id}/test")
    public ConnectorService.ConnectorView test(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        return service.test(CurrentUser.id(user), tenant, id);
    }

    public record CapabilityRequest(boolean enabled) {}
    @PatchMapping("/{id}/capabilities/{action}")
    public ConnectorService.ConnectorView capability(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id, @PathVariable String action,
            @RequestBody CapabilityRequest request) {
        return service.capability(CurrentUser.id(user), tenant, id, action, request.enabled());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthPrincipal user, @PathVariable UUID tenant,
                       @PathVariable UUID id) {
        service.delete(CurrentUser.id(user), tenant, id);
    }

    @GetMapping("/{id}/payments/failed")
    public List<EnterpriseAdapter.Payment> failedPayments(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        return service.failedPayments(CurrentUser.id(user), tenant, id);
    }

    @GetMapping("/{id}/payments/{payment}")
    public EnterpriseAdapter.Payment payment(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id, @PathVariable String payment) {
        return service.payment(CurrentUser.id(user), tenant, id, payment);
    }

    @GetMapping("/{id}/customers/{customer}")
    public EnterpriseAdapter.Customer customer(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id, @PathVariable String customer) {
        return service.customer(CurrentUser.id(user), tenant, id, customer);
    }

    @GetMapping("/{id}/employees")
    public List<EnterpriseAdapter.Employee> employees(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        return service.employees(CurrentUser.id(user), tenant, id);
    }

    @PostMapping("/{id}/followups")
    @ResponseStatus(HttpStatus.CREATED)
    public EnterpriseAdapter.Followup followup(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id,
            @Valid @RequestBody ConnectorService.FollowupRequest request) {
        return service.followup(CurrentUser.id(user), tenant, id, request);
    }

    @PostMapping("/{id}/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public EnterpriseAdapter.Draft draft(@AuthenticationPrincipal AuthPrincipal user,
            @PathVariable UUID tenant, @PathVariable UUID id,
            @Valid @RequestBody ConnectorService.DraftRequest request) {
        return service.draft(CurrentUser.id(user), tenant, id, request);
    }
}

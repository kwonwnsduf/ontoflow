package ai.ontoflow.platform.connector;

import ai.ontoflow.platform.common.security.AuthPrincipal;
import ai.ontoflow.platform.common.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenant}/sources")
public class ExternalSourceController {
    private final ExternalSourceService service;
    public ExternalSourceController(ExternalSourceService service) { this.service = service; }

    @GetMapping("/registry")
    public List<ExternalSourceService.RegistryView> registry(@AuthenticationPrincipal AuthPrincipal principal,
                                                               @PathVariable UUID tenant) {
        return service.registry(CurrentUser.id(principal), tenant);
    }

    @GetMapping("/available")
    public List<ExternalSourceService.AvailableResource> available(@AuthenticationPrincipal AuthPrincipal principal,
                                                                     @PathVariable UUID tenant) {
        return service.available(CurrentUser.id(principal), tenant);
    }

    @GetMapping
    public List<ExternalSourceService.SourceView> list(@AuthenticationPrincipal AuthPrincipal principal,
                                                        @PathVariable UUID tenant) {
        return service.list(CurrentUser.id(principal), tenant);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExternalSourceService.SourceView connect(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @RequestBody ExternalSourceService.ConnectRequest request) {
        return service.connect(CurrentUser.id(principal), tenant, request);
    }

    @GetMapping("/{id}")
    public ExternalSourceService.SourceView get(@AuthenticationPrincipal AuthPrincipal principal,
                                                @PathVariable UUID tenant, @PathVariable UUID id) {
        return service.get(CurrentUser.id(principal), tenant, id);
    }

    @PostMapping("/{id}/test")
    public ExternalSourceService.SourceView test(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        return service.test(CurrentUser.id(principal), tenant, id);
    }

    public record CapabilityRequest(boolean enabled) {}
    public record CredentialRequest(String credential) {}
    @PatchMapping("/{id}/credential")
    public ExternalSourceService.SourceView rotate(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id, @RequestBody CredentialRequest request) {
        return service.rotate(CurrentUser.id(principal), tenant, id, request.credential());
    }
    @PatchMapping("/{id}/capability")
    public ExternalSourceService.SourceView capability(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id, @RequestBody CapabilityRequest request) {
        return service.capability(CurrentUser.id(principal), tenant, id, request.enabled());
    }

    @GetMapping("/{id}/read")
    public SourceReader.Page read(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        return service.read(CurrentUser.id(principal), tenant, id, cursor, limit);
    }

    @PostMapping("/{id}/sync")
    public Map<String, Integer> sync(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        return Map.of("processed", service.sync(CurrentUser.id(principal), tenant, id));
    }

    @GetMapping("/{id}/records")
    public List<ExternalSourceService.RecordView> records(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return service.records(CurrentUser.id(principal), tenant, id, limit);
    }

    @GetMapping("/{id}/provenance")
    public List<ExternalSourceService.RecordView> provenance(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id, @RequestParam(defaultValue = "50") int limit) {
        return service.provenance(CurrentUser.id(principal), tenant, id, limit);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @PathVariable UUID id) {
        service.disconnect(CurrentUser.id(principal), tenant, id);
    }

    public record SlackStartRequest(String name, String resourceKey) {}
    @PostMapping("/slack/authorize")
    public Map<String, String> beginSlack(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @RequestBody SlackStartRequest request) {
        return Map.of("authorizeUrl", service.beginSlack(CurrentUser.id(principal), tenant,
                request.name(), request.resourceKey()));
    }

    @GetMapping("/github/install-url")
    public Map<String, String> githubInstallUrl(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant) {
        return Map.of("installUrl", service.githubInstallUrl(CurrentUser.id(principal), tenant));
    }

    public record GithubStartRequest(String name, String resourceKey, String installationId) {}
    @PostMapping("/github/authorize")
    public Map<String, String> beginGithub(@AuthenticationPrincipal AuthPrincipal principal,
            @PathVariable UUID tenant, @RequestBody GithubStartRequest request) {
        return Map.of("authorizeUrl", service.beginGithub(CurrentUser.id(principal), tenant,
                request.name(), request.resourceKey(), request.installationId()));
    }
}

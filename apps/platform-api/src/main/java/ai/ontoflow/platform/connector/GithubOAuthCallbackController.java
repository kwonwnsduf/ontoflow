package ai.ontoflow.platform.connector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/sources/github/callback")
public class GithubOAuthCallbackController {
    private final ExternalSourceService service;
    private final String webOrigin;
    public GithubOAuthCallbackController(ExternalSourceService service,
            @Value("${ontoflow.auth.web-origin:http://localhost:3000}") String webOrigin) {
        this.service = service;
        this.webOrigin = webOrigin;
    }

    @GetMapping
    public ResponseEntity<Void> callback(@RequestParam String state, @RequestParam String code) {
        var source = service.finishGithub(state, code);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(webOrigin + "/dashboard/" + source.tenantId() + "/sources?oauth=github"))
                .build();
    }
}

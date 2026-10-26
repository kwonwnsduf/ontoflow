package ai.ontoflow.platform.web;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {
    @GetMapping("/api/v1")
    Map<String, String> index() {
        return Map.of("service", "platform-api", "status", "ready");
    }
}


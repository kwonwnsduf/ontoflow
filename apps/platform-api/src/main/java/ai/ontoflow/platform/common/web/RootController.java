package ai.ontoflow.platform.common.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RootController {
    @GetMapping("/api/v1")
    Map<String, String> index() {
        return Map.of("service", "platform-api", "status", "ready");
    }
}

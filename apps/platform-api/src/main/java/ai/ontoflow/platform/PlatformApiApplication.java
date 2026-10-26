package ai.ontoflow.platform;

import ai.ontoflow.platform.config.PlatformSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class PlatformApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformApiApplication.class, args);
    }
}

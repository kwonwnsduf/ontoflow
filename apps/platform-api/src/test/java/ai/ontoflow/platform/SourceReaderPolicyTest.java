package ai.ontoflow.platform;

import ai.ontoflow.platform.connector.SourceCatalog;
import ai.ontoflow.platform.connector.SourceReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SourceReaderPolicyTest {
    private final SourceReader reader = new SourceReader(new ObjectMapper(), "", "", "", "", "", "", "", "", "", true);

    @Test
    void rejectsUnsafeDatabaseAndProviderResourcesBeforeNetworkAccess() {
        SourceCatalog.Resource database = new SourceCatalog.Resource();
        database.setJdbcUrl("jdbc:postgresql://localhost/sample");
        database.setView("approved_view;DELETE FROM customers");
        database.setIdColumn("id");
        assertThrows(ResponseStatusException.class, () -> reader.read("POSTGRESQL", database, "{}", null, 10));

        SourceCatalog.Resource github = new SourceCatalog.Resource();
        github.setRepository("https://attacker.invalid/repo");
        assertThrows(ResponseStatusException.class, () -> reader.read("GITHUB", github, "1", null, 10));

        SourceCatalog.Resource slack = new SourceCatalog.Resource();
        slack.setChannelId("D123456");
        assertThrows(ResponseStatusException.class, () -> reader.read("SLACK", slack, "token", null, 10));
    }
}

package ai.ontoflow.platform.connector;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Server-owned allowlist. No client-supplied SQL, host, repository or channel is used. */
@Component
@ConfigurationProperties(prefix = "ontoflow.connector")
public class SourceCatalog {
    private Map<String, Resource> resources = new HashMap<>();
    public Map<String, Resource> getResources() { return resources; }
    public void setResources(Map<String, Resource> resources) { this.resources = resources; }

    public static class Resource {
        private String type;
        private String jdbcUrl;
        private String view;
        private String idColumn;
        private String repository;
        private String channelId;
        private String tenantId;
        public String getType() { return type; }
        public void setType(String value) { type = value; }
        public String getJdbcUrl() { return jdbcUrl; }
        public void setJdbcUrl(String value) { jdbcUrl = value; }
        public String getView() { return view; }
        public void setView(String value) { view = value; }
        public String getIdColumn() { return idColumn; }
        public void setIdColumn(String value) { idColumn = value; }
        public String getRepository() { return repository; }
        public void setRepository(String value) { repository = value; }
        public String getChannelId() { return channelId; }
        public void setChannelId(String value) { channelId = value; }
        public String getTenantId() { return tenantId; }
        public void setTenantId(String value) { tenantId = value; }
    }
}

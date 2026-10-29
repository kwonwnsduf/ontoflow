package ai.ontoflow.platform.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MockEnterpriseAdapter {
    private final String baseUrl;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public MockEnterpriseAdapter(@Value("${ontoflow.connector.mock-url:http://localhost:8090}") String baseUrl, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.mapper = mapper;
    }

    public EnterpriseAdapter forType(String type) {
        if (!List.of("ALPHA", "BETA").contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported system");
        return new Adapter(type.toLowerCase(), type.equals("BETA"));
    }

    private JsonNode call(String system, String credential, String method, String path, Object payload) {
        try {
            String url = baseUrl + "/systems/" + system + path;
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .header("Authorization", "Bearer " + credential)
                    .header("Accept", "application/json");
            if (payload == null) builder.GET();
            else builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Connector credential rejected");
            if (response.statusCode() == 404) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Enterprise record not found");
            if (response.statusCode() >= 400) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Enterprise request failed");
            return mapper.readTree(response.body());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Enterprise request timed out");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Enterprise system unavailable");
        }
    }

    private String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private final class Adapter implements EnterpriseAdapter {
        private final String system;
        private final boolean beta;

        Adapter(String system, boolean beta) {
            this.system = system;
            this.beta = beta;
        }

        @Override public void test(String credential) { call(system, credential, "GET", "/payments", null); }

        @Override public List<Payment> failedPayments(String credential) {
            List<Payment> result = new ArrayList<>();
            for (JsonNode item : call(system, credential, "GET", "/payments", null).path("items")) {
                Payment payment = paymentOf(item);
                if ("FAILED".equals(payment.status())) result.add(payment);
            }
            return result;
        }

        @Override public Payment payment(String credential, String id) {
            return paymentOf(call(system, credential, "GET", "/payments/" + segment(id), null));
        }

        private Payment paymentOf(JsonNode item) {
            String status = item.path("status").asText();
            if (beta) status = switch (status) { case "DECLINED" -> "FAILED"; case "SETTLED" -> "PAID"; default -> status; };
            return new Payment(item.path("id").asText(), item.path("customerId").asText(),
                    item.path("amount").asLong(), item.path("currency").asText(), status,
                    item.path("failedAt").isNull() ? null : item.path("failedAt").asText(null));
        }

        @Override public Customer customer(String credential, String id) {
            JsonNode item = call(system, credential, "GET", "/customers/" + segment(id), null);
            return new Customer(item.path("id").asText(), item.path("name").asText(), item.path("email").asText());
        }

        @Override public List<Employee> employees(String credential) {
            List<Employee> result = new ArrayList<>();
            for (JsonNode item : call(system, credential, "GET", "/employees", null).path("items"))
                result.add(new Employee(item.path("id").asText(), item.path("name").asText()));
            return result;
        }

        @Override public Followup createFollowup(String credential, String customerId, String assigneeId, String summary) {
            JsonNode item = call(system, credential, "POST", "/tasks",
                    Map.of("customerId", customerId, "assigneeId", assigneeId, "summary", summary));
            return new Followup(item.path("id").asText(), customerId, assigneeId, summary);
        }

        @Override public Draft createDraft(String credential, String to, String subject, String content) {
            JsonNode item = call(system, credential, "POST", "/drafts",
                    Map.of("to", to, "subject", subject, "content", content));
            return new Draft(item.path("id").asText(), to, subject, content);
        }
    }
}

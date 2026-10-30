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
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import io.jsonwebtoken.Jwts;

@Component
public class SourceReader {
    public record Item(String id, String url, String updatedAt, JsonNode body) {}
    public record Page(List<Item> items, String nextCursor, String scope) {}
    public record SlackTokens(String accessToken, String refreshToken, long expiresAt, String scopes) {}
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)?");
    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    private static final Pattern CHANNEL = Pattern.compile("[CG][A-Z0-9]+");
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String githubAppId;
    private final String githubPrivateKey;
    private final String githubClientId;
    private final String githubClientSecret;
    private final String githubRedirectUri;
    private final String githubAppSlug;
    private final String slackClientId;
    private final String slackClientSecret;
    private final String slackRedirectUri;
    private final boolean requireTls;

    public SourceReader(ObjectMapper json,
            @Value("${ontoflow.connector.github.app-id:}") String githubAppId,
            @Value("${ontoflow.connector.github.private-key-base64:}") String githubPrivateKey,
            @Value("${ontoflow.connector.github.client-id:}") String githubClientId,
            @Value("${ontoflow.connector.github.client-secret:}") String githubClientSecret,
            @Value("${ontoflow.connector.github.redirect-uri:}") String githubRedirectUri,
            @Value("${ontoflow.connector.github.app-slug:}") String githubAppSlug,
            @Value("${ontoflow.connector.slack.client-id:}") String slackClientId,
            @Value("${ontoflow.connector.slack.client-secret:}") String slackClientSecret,
            @Value("${ontoflow.connector.slack.redirect-uri:}") String slackRedirectUri,
            @Value("${ontoflow.connector.require-tls:true}") boolean requireTls) {
        this.json = json;
        this.githubAppId = githubAppId;
        this.githubPrivateKey = githubPrivateKey;
        this.githubClientId = githubClientId;
        this.githubClientSecret = githubClientSecret;
        this.githubRedirectUri = githubRedirectUri;
        this.githubAppSlug = githubAppSlug;
        this.slackClientId = slackClientId;
        this.slackClientSecret = slackClientSecret;
        this.slackRedirectUri = slackRedirectUri;
        this.requireTls = requireTls;
    }

    public Page read(String type, SourceCatalog.Resource resource, String credential,
                     String cursor, int limit) {
        return switch (type) {
            case "POSTGRESQL", "MYSQL" -> database(type, resource, credential, cursor, limit);
            case "GITHUB" -> github(resource, credential, cursor, limit);
            case "SLACK" -> slack(resource, credential, cursor, limit);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported source");
        };
    }

    private Page database(String type, SourceCatalog.Resource resource, String credential,
                          String cursor, int limit) {
        String url = resource.getJdbcUrl();
        String view = resource.getView();
        String id = resource.getIdColumn();
        if (url == null || !url.startsWith(type.equals("MYSQL") ? "jdbc:mysql://" : "jdbc:postgresql://")
                || view == null || id == null || !IDENTIFIER.matcher(view).matches()
                || !IDENTIFIER.matcher(id).matches() || id.contains("."))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server source configuration");
        if (requireTls && !(type.equals("MYSQL")
                ? Pattern.compile("(?i)[?&]sslMode=VERIFY_IDENTITY(?:&|$)").matcher(url).find()
                : Pattern.compile("(?i)[?&]sslmode=verify-full(?:&|$)").matcher(url).find()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verified TLS is required for source database");
        try {
            JsonNode login = json.readTree(credential);
            String username = login.path("username").asText();
            String password = login.path("password").asText();
            if (username.isBlank() || password.isBlank()) throw new IllegalArgumentException();
            int offset = cursor == null ? 0 : Integer.parseInt(cursor);
            if (offset < 0 || offset > 1000000)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source cursor");
            List<Item> items = new ArrayList<>();
            java.util.Properties properties = new java.util.Properties();
            properties.setProperty("user", username);
            properties.setProperty("password", password);
            properties.setProperty("connectTimeout", type.equals("MYSQL") ? "3000" : "3");
            properties.setProperty("socketTimeout", type.equals("MYSQL") ? "5000" : "5");
            try (var connection = DriverManager.getConnection(url, properties)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                if (type.equals("POSTGRESQL")) {
                    try (var grants = connection.prepareStatement("SELECT has_table_privilege(current_user, ?, 'INSERT') OR "
                            + "has_table_privilege(current_user, ?, 'UPDATE') OR "
                            + "has_table_privilege(current_user, ?, 'DELETE') OR "
                            + "has_table_privilege(current_user, ?, 'TRUNCATE')")) {
                        for (int i = 1; i <= 4; i++) grants.setString(i, view);
                        try (var result = grants.executeQuery()) {
                            if (result.next() && result.getBoolean(1))
                                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source account must be read-only");
                        }
                    }
                } else {
                    try (var grants = connection.createStatement(); var result = grants.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
                        while (result.next()) {
                            String grant = result.getString(1).toUpperCase(java.util.Locale.ROOT);
                            if (Pattern.compile("\\b(ALL PRIVILEGES|INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|EXECUTE|TRIGGER)\\b").matcher(grant).find())
                                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source account must be read-only");
                        }
                    }
                }
                try (var statement = connection.prepareStatement("SELECT * FROM " + view + " ORDER BY " + id + " LIMIT ? OFFSET ?")) {
                    statement.setQueryTimeout(5);
                    statement.setMaxRows(limit);
                    statement.setInt(1, limit);
                    statement.setInt(2, offset);
                    try (var rows = statement.executeQuery()) {
                        var metadata = rows.getMetaData();
                        while (rows.next()) {
                            var body = json.createObjectNode();
                            for (int column = 1; column <= metadata.getColumnCount(); column++)
                                body.putPOJO(metadata.getColumnLabel(column), rows.getObject(column));
                            String recordId = rows.getString(id);
                            if (recordId != null) items.add(new Item(recordId, null, null, body));
                        }
                    }
                }
                connection.rollback();
            }
            return new Page(items, items.size() == limit ? String.valueOf(offset + items.size()) : null, view);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source cursor");
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Read-only source query failed");
        }
    }

    private String appJwt() {
        try {
            byte[] bytes = Base64.getDecoder().decode(githubPrivateKey);
            PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
            Instant now = Instant.now();
            return Jwts.builder().issuer(githubAppId).issuedAt(java.util.Date.from(now.minusSeconds(30)))
                    .expiration(java.util.Date.from(now.plusSeconds(540))).signWith(key, Jwts.SIG.RS256).compact();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub App is not configured");
        }
    }

    private JsonNode request(String method, String url, String token, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").header("Authorization", "Bearer " + token);
            if (url.startsWith("https://api.github.com/")) request.header("X-GitHub-Api-Version", "2022-11-28");
            if (body == null) request.GET();
            else request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
                boolean limited = response.statusCode() == 429 || response.statusCode() == 403
                        && response.headers().firstValue("x-ratelimit-remaining").orElse("").equals("0");
                if (!limited || attempt == 2) break;
                long seconds;
                try { seconds = Long.parseLong(response.headers().firstValue("Retry-After").orElse("1")); }
                catch (NumberFormatException e) { seconds = 1; }
                Thread.sleep(Math.min(2, Math.max(1, seconds)) * 1000L);
            }
            if (response.statusCode() == 429 || response.statusCode() == 403 && response.headers().firstValue("x-ratelimit-remaining").orElse("").equals("0"))
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Source rate limited");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source authorization denied");
            if (response.statusCode() == 422)
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Source scope denied");
            if (response.statusCode() == 404)
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source resource unavailable");
            if (response.statusCode() >= 400)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source request failed");
            return json.readTree(response.body());
        } catch (ResponseStatusException e) { throw e; }
        catch (java.net.http.HttpTimeoutException e) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Source timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Source request interrupted");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Source unavailable");
        }
    }

    private String githubToken(String installation, String repository) {
        if (!installation.matches("[1-9][0-9]{0,18}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid installation");
        String repositoryName = repository.substring(repository.indexOf('/') + 1);
        String body = "{\"repositories\":[\"" + repositoryName + "\"],\"permissions\":{\"contents\":\"read\",\"issues\":\"read\",\"pull_requests\":\"read\"}}";
        JsonNode response = request("POST", "https://api.github.com/app/installations/" + installation + "/access_tokens", appJwt(), body);
        String token = response.path("token").asText();
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Installation token unavailable");
        return token;
    }

    private Page github(SourceCatalog.Resource resource, String installation, String cursor, int limit) {
        String repo = resource.getRepository();
        if (repo == null || !REPOSITORY.matcher(repo).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server repository configuration");
        String token = githubToken(installation, repo);
        int page;
        try { page = cursor == null ? 1 : Integer.parseInt(cursor); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source cursor");
        }
        if (page < 1 || page > 10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid cursor");
        // Installation token, not an app JWT, enforces the installation's repository scope.
        JsonNode repository = request("GET", "https://api.github.com/repos/" + repo, token, null);
        if (!repo.equalsIgnoreCase(repository.path("full_name").asText()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository outside installation");
        List<Item> items = new ArrayList<>();
        boolean hasMore = false;
        for (String kind : List.of("issues", "pulls", "commits")) {
            JsonNode response = request("GET", "https://api.github.com/repos/" + repo + "/" + kind
                    + "?state=all&per_page=" + limit + "&page=" + page, token, null);
            if (response.isArray() && response.size() == limit) hasMore = true;
            for (JsonNode item : response) {
                String id = kind + ":" + item.path("id").asText(item.path("sha").asText());
                items.add(new Item(id, item.path("html_url").asText(null),
                        item.path("updated_at").asText(null), item));
            }
        }
        items.add(new Item("repository:" + repo, repository.path("html_url").asText(null),
                repository.path("updated_at").asText(null), repository));
        return new Page(items, hasMore ? String.valueOf(page + 1) : null, repo);
    }

    public String githubInstallUrl() {
        if (!githubAppSlug.matches("[A-Za-z0-9-]{1,100}"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub App is not configured");
        return "https://github.com/apps/" + githubAppSlug + "/installations/new";
    }

    public String githubAuthorizeUrl(String state) {
        if (githubClientId.isBlank() || githubRedirectUri.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub authorization is not configured");
        return "https://github.com/login/oauth/authorize?client_id=" + URLEncoder.encode(githubClientId, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(githubRedirectUri, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    public void verifyGithubGrant(String code, String installation, String repository) {
        if (!installation.matches("[1-9][0-9]{0,18}") || !REPOSITORY.matcher(repository).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid GitHub installation");
        String userToken;
        try {
            String form = "client_id=" + URLEncoder.encode(githubClientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(githubClientSecret, StandardCharsets.UTF_8)
                    + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(githubRedirectUri, StandardCharsets.UTF_8);
            HttpRequest exchange = HttpRequest.newBuilder(URI.create("https://github.com/login/oauth/access_token"))
                    .timeout(Duration.ofSeconds(8)).header("Accept", "application/json")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            JsonNode response = json.readTree(http.send(exchange, HttpResponse.BodyHandlers.ofString()).body());
            userToken = response.path("access_token").asText();
            if (userToken.isBlank()) throw new IllegalStateException();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub user authorization failed");
        }
        for (int page = 1; page <= 100; page++) {
            JsonNode listing = request("GET", "https://api.github.com/user/installations/" + installation
                    + "/repositories?per_page=100&page=" + page, userToken, null);
            JsonNode repos = listing.path("repositories");
            for (JsonNode item : repos) {
                if (repository.equalsIgnoreCase(item.path("full_name").asText())) return;
            }
            if (!repos.isArray() || repos.size() < 100) break;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Repository is not accessible to the installer");
    }

    private Page slack(SourceCatalog.Resource resource, String token, String cursor, int limit) {
        String channel = resource.getChannelId();
        if (channel == null || !CHANNEL.matcher(channel).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server channel configuration");
        JsonNode info = request("GET", "https://slack.com/api/conversations.info?channel=" + channel, token, null);
        if (!info.path("ok").asBoolean() || !info.path("channel").path("is_member").asBoolean())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Channel access denied");
        if (info.path("channel").path("is_im").asBoolean() || info.path("channel").path("is_mpim").asBoolean())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Direct messages are not enabled");
        String url = "https://slack.com/api/conversations.history?channel=" + channel + "&limit=" + limit;
        if (cursor != null) url += "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8);
        JsonNode result = request("GET", url, token, null);
        if (!result.path("ok").asBoolean())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Message access denied");
        List<Item> items = new ArrayList<>();
        for (JsonNode message : result.path("messages"))
            items.add(new Item(message.path("ts").asText(), null, null, message));
        String next = result.path("response_metadata").path("next_cursor").asText();
        return new Page(items, next.isBlank() ? null : next, channel);
    }

    public String slackAuthorizeUrl(String state, String channelId) {
        if (slackClientId.isBlank() || slackRedirectUri.isBlank())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Slack OAuth is not configured");
        if (channelId == null || !CHANNEL.matcher(channelId).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid server channel configuration");
        String scopes = channelId.startsWith("G") ? "groups:read,groups:history" : "channels:read,channels:history";
        return "https://slack.com/oauth/v2/authorize?client_id=" + URLEncoder.encode(slackClientId, StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode(scopes, StandardCharsets.UTF_8)
                + "&redirect_uri=" + URLEncoder.encode(slackRedirectUri, StandardCharsets.UTF_8)
                + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8);
    }

    private SlackTokens slackTokens(JsonNode response) {
        if (!response.path("ok").asBoolean() || response.path("access_token").asText().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Slack authorization failed");
        long expires = response.path("expires_in").asLong(0);
        return new SlackTokens(response.path("access_token").asText(),
                response.path("refresh_token").asText(null),
                expires > 0 ? Instant.now().plusSeconds(expires).getEpochSecond() : 0,
                response.path("scope").asText(""));
    }

    public SlackTokens exchangeSlackCode(String code) {
        try {
            String form = "client_id=" + URLEncoder.encode(slackClientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(slackClientSecret, StandardCharsets.UTF_8)
                    + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(slackRedirectUri, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://slack.com/api/oauth.v2.access"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            JsonNode response = json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body());
            return slackTokens(response);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Slack authorization failed");
        }
    }

    public SlackTokens refreshSlack(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank())
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Slack authorization expired");
        try {
            String form = "client_id=" + URLEncoder.encode(slackClientId, StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(slackClientSecret, StandardCharsets.UTF_8)
                    + "&grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://slack.com/api/oauth.v2.access"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            return slackTokens(json.readTree(http.send(request, HttpResponse.BodyHandlers.ofString()).body()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Slack token refresh failed");
        }
    }

    public void revokeSlack(String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://slack.com/api/auth.revoke"))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // Local access is removed even if the provider is temporarily unavailable.
        }
    }
}

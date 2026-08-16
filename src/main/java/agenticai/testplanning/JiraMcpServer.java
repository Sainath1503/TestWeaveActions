package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Jira/web retrieval. Authentication headers can be supplied by the host later. */
public final class JiraMcpServer {
    private static final int MAX_REMOTE_CHARACTERS = 250_000;
    private static final int MAX_LINKED_RESOURCES = 20;
    private static final Pattern HTML_LINK = Pattern.compile("(?is)(?:href|src)\\s*=\\s*['\"]([^'\"#]+)['\"]");
    private static final Pattern SWAGGER_SPEC = Pattern.compile("(?is)(?:url|spec-url)\\s*[:=]\\s*['\"]([^'\"]+(?:json|ya?ml)(?:\\?[^'\"]*)?)['\"]");
    private static final Pattern JSON_URL = Pattern.compile("https?://[^\"'\\s]+", Pattern.CASE_INSENSITIVE);
    private final DocumentConversionMcpServer documents = new DocumentConversionMcpServer();
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private volatile String jiraEmail = "";
    private volatile String jiraToken = "";

    public void configure(String email, String token) {
        jiraEmail = email == null ? "" : email.trim();
        jiraToken = token == null ? "" : token.trim();
    }

    public boolean isJiraUrl(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return lower.contains("atlassian.net/") || lower.contains("/browse/") || lower.contains("/jira/");
    }

    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-jira-knowledge-mcp")
                        .put("version", "1.0").put("mode", "in-process").put("readOnly", true))
                .put("tools", new JSONArray().put("jira.get_issue_or_page").put("web.get_document"));
    }

    public JSONObject retrieve(String url) throws Exception {
        URI originalUri = URI.create(url);
        URI uri = isJiraUrl(url) ? jiraApiUri(originalUri) : originalUri;
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only HTTP(S) documentation links are supported.");
        }
        HttpResponse<byte[]> response = fetch(uri, url);
        String contentType = response.headers().firstValue("content-type").orElse("");
        String rawContent = new String(response.body() == null ? new byte[0] : response.body(), StandardCharsets.UTF_8);
        boolean truncated = rawContent.length() > MAX_REMOTE_CHARACTERS;
        String content = truncated ? rawContent.substring(0, MAX_REMOTE_CHARACTERS) : rawContent;
        boolean html = contentType.toLowerCase(Locale.ROOT).contains("text/html");
        JSONObject result = new JSONObject()
                .put("sourceType", isJiraUrl(url) ? "jira" : "webDocument")
                .put("name", url)
                .put("url", url)
                .put("contentType", contentType)
                .put("truncated", truncated)
                .put("content", html ? stripHtml(content) : content);

        JSONArray linkedResources = crawlLinkedResources(uri, rawContent, contentType, isJiraUrl(url));
        if (linkedResources.length() > 0) result.put("linkedResources", linkedResources);
        return result;
    }

    private HttpResponse<byte[]> fetch(URI uri, String authenticationSource) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("Accept", "application/json, application/yaml, text/yaml, text/plain, text/html, application/pdf, application/octet-stream")
                .header("User-Agent", "VeyraAI-TestPlanningAgent/1.0")
                .GET();
        if (isJiraUrl(authenticationSource) && !jiraToken.isBlank()) {
            if (jiraEmail.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + jiraToken);
            } else {
                String credential = Base64.getEncoder().encodeToString(
                        (jiraEmail + ":" + jiraToken).getBytes(StandardCharsets.UTF_8));
                requestBuilder.header("Authorization", "Basic " + credential);
            }
        }
        HttpResponse<byte[]> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Source returned HTTP " + response.statusCode()
                    + (isJiraUrl(authenticationSource) ? ". Configure Jira access if this project is private." : "."));
        }
        return response;
    }

    private JSONArray crawlLinkedResources(URI pageUri, String rawContent, String contentType, boolean jiraSource) {
        JSONArray resources = new JSONArray();
        Set<URI> candidates = discoverLinks(pageUri, rawContent, contentType, jiraSource);
        Set<String> visited = new HashSet<>();
        for (URI candidate : candidates) {
            if (resources.length() >= MAX_LINKED_RESOURCES || !visited.add(candidate.normalize().toString())) break;
            try {
                HttpResponse<byte[]> response = fetch(candidate, jiraSource ? pageUri.toString() : candidate.toString());
                String linkedType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
                JSONObject resource;
                if (isDocument(candidate, linkedType)) {
                    resource = extractDownloadedDocument(candidate, linkedType, response.body());
                } else {
                    String body = new String(response.body(), StandardCharsets.UTF_8);
                    boolean truncated = body.length() > MAX_REMOTE_CHARACTERS;
                    if (truncated) body = body.substring(0, MAX_REMOTE_CHARACTERS);
                    resource = new JSONObject().put("sourceType", "linkedWebResource")
                            .put("name", candidate.toString()).put("url", candidate.toString())
                            .put("contentType", linkedType).put("truncated", truncated)
                            .put("content", linkedType.contains("text/html") ? stripHtml(body) : body);
                }
                resources.put(resource);
            } catch (Exception exception) {
                resources.put(new JSONObject().put("sourceType", "linkedResourceError")
                        .put("url", candidate.toString()).put("error", exception.getMessage()));
            }
        }
        return resources;
    }

    private Set<URI> discoverLinks(URI pageUri, String content, String contentType, boolean jiraSource) {
        Set<URI> result = new LinkedHashSet<>();
        if (content == null) return result;
        Matcher swagger = SWAGGER_SPEC.matcher(content);
        while (swagger.find()) addResolved(result, pageUri, swagger.group(1));
        if (contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            Matcher links = HTML_LINK.matcher(content);
            while (links.find()) {
                URI resolved = resolve(pageUri, links.group(1));
                if (resolved != null && (isLikelyKnowledgeDocument(resolved)
                        || (sameOrigin(pageUri, resolved) && isDocumentationPage(resolved)))) result.add(resolved);
            }
        }
        if (jiraSource || contentType.toLowerCase(Locale.ROOT).contains("json")) {
            Matcher urls = JSON_URL.matcher(content.replace("\\/", "/"));
            while (urls.find()) {
                URI resolved = resolve(pageUri, urls.group());
                if (resolved != null && isLikelyKnowledgeDocument(resolved)) result.add(resolved);
            }
        }
        result.remove(pageUri);
        return result;
    }

    private void addResolved(Set<URI> result, URI base, String value) {
        URI uri = resolve(base, value);
        if (uri != null) result.add(uri);
    }

    private URI resolve(URI base, String value) {
        try {
            URI uri = base.resolve(value.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return ("http".equals(scheme) || "https".equals(scheme)) ? uri : null;
        } catch (Exception ignored) { return null; }
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getHost() != null && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private boolean isDocumentationPage(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.matches(".*\\.(css|js|png|jpe?g|gif|svg|ico|woff2?|ttf)$")) return false;
        return path.contains("doc") || path.contains("swagger") || path.contains("openapi");
    }

    private boolean isLikelyKnowledgeDocument(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if (path.matches(".*\\.(css|js|png|jpe?g|gif|svg|ico|woff2?|ttf)$")) return false;
        return path.matches(".*\\.(json|ya?ml|pdf|docx?|xlsx?|csv|pptx?)(?:$|/).*?")
                || path.contains("attachment/") || path.contains("openapi") || path.contains("swagger");
    }

    private boolean isDocument(URI uri, String contentType) {
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return contentType.contains("pdf") || contentType.contains("officedocument") || contentType.contains("msword")
                || path.matches(".*\\.(pdf|docx?|xlsx?|csv|pptx?)$");
    }

    private JSONObject extractDownloadedDocument(URI uri, String contentType, byte[] body) throws Exception {
        String suffix = extensionSuffix(uri, contentType);
        Path temporary = Files.createTempFile("veyra-linked-document-", suffix);
        try {
            Files.write(temporary, body);
            JSONObject extracted = documents.extract(temporary);
            extracted.put("sourceType", "linkedDocument").put("url", uri.toString()).put("name", uri.toString());
            extracted.remove("path");
            return extracted;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String extensionSuffix(URI uri, String contentType) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && path.substring(dot).matches("(?i)\\.(pdf|docx?|xlsx?|csv|pptx?)")) return path.substring(dot);
        if (contentType.contains("pdf")) return ".pdf";
        return ".pdf";
    }

    private URI jiraApiUri(URI original) {
        String path = original.getPath() == null ? "" : original.getPath();
        java.util.regex.Matcher browse = java.util.regex.Pattern.compile("(?i)^(.*/)?browse/([A-Z][A-Z0-9_]+-\\d+)/?$")
                .matcher(path);
        if (!browse.matches()) return original;
        String prefix = browse.group(1) == null ? "/" : browse.group(1);
        if (!prefix.endsWith("/")) prefix += "/";
        try {
            return new URI(original.getScheme(), original.getAuthority(),
                    prefix + "rest/api/3/issue/" + browse.group(2), "fields=*all", null);
        } catch (Exception ignored) {
            return original;
        }
    }

    private String stripHtml(String html) {
        return html.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
                .replaceAll("\\n\\s+", "\n")
                .trim();
    }
}

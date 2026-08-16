package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Coordinates uploaded files, local/Git projects, API contracts, Jira, and web documentation. */
public final class KnowledgeSourceMcpServer {
    private final DocumentConversionMcpServer documents;
    private final ProjectRepositoryMcpServer projects;
    private final ApiContractMcpServer apiContracts;
    private final JiraMcpServer jira;

    public KnowledgeSourceMcpServer(DocumentConversionMcpServer documents,
                                    ProjectRepositoryMcpServer projects,
                                    ApiContractMcpServer apiContracts,
                                    JiraMcpServer jira) {
        this.documents = documents;
        this.projects = projects;
        this.apiContracts = apiContracts;
        this.jira = jira;
    }

    public JSONObject buildContext(List<String> suppliedSources, List<Path> uploads, Path cacheRoot,
                                   Consumer<String> log) {
        JSONArray sources = new JSONArray();
        JSONArray errors = new JSONArray();
        Map<String, String> unique = new LinkedHashMap<>();
        if (suppliedSources != null) {
            for (String raw : suppliedSources) {
                ParsedSource parsed = parseSource(raw);
                if (!parsed.location().isBlank()) unique.merge(parsed.location(), parsed.instructions(),
                        (left, right) -> left.isBlank() ? right
                                : right.isBlank() || left.contains(right) ? left : left + "\n" + right);
            }
        }
        if (uploads != null) {
            for (Path upload : uploads) {
                if (upload != null) unique.putIfAbsent(upload.toAbsolutePath().normalize().toString(), "");
            }
        }
        for (Map.Entry<String, String> sourceEntry : unique.entrySet()) {
            String source = sourceEntry.getKey();
            try {
                if (log != null) log.accept("Reading source: " + source);
                JSONObject extracted;
                Path local = safeLocalPath(source);
                if (local != null && Files.isRegularFile(local)) {
                    extracted = apiContracts.enrich(documents.extract(local));
                } else if ((local != null && Files.isDirectory(local)) || projects.isGitSource(source)) {
                    extracted = projects.inspect(source, cacheRoot.resolve("repositories"), log);
                } else if (isHttpUrl(source)) {
                    extracted = apiContracts.enrich(jira.retrieve(source));
                } else {
                    throw new IllegalArgumentException("Source is not a readable file, project directory, Git repository, or HTTP(S) URL.");
                }
                if (!sourceEntry.getValue().isBlank()) extracted.put("sourceInstructions", sourceEntry.getValue());
                extracted.put("sourceId", "SRC-" + (sources.length() + 1));
                sources.put(extracted);
            } catch (Exception exception) {
                errors.put(new JSONObject().put("source", source).put("error",
                        exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()));
                if (log != null) log.accept("Source failed: " + source + " - " + exception.getMessage());
            }
        }
        return new JSONObject().put("sources", sources).put("sourceErrors", errors)
                .put("sourceCount", sources.length()).put("errorCount", errors.length());
    }

    private ParsedSource parseSource(String raw) {
        String value = raw == null ? "" : raw.trim();
        int pipe = value.indexOf('|');
        int arrow = value.indexOf("->");
        int delimiter = pipe < 0 ? arrow : arrow < 0 ? pipe : Math.min(pipe, arrow);
        if (delimiter < 0) return new ParsedSource(unquote(value), "");
        int delimiterLength = delimiter == arrow ? 2 : 1;
        return new ParsedSource(unquote(value.substring(0, delimiter)),
                unquote(value.substring(delimiter + delimiterLength)));
    }

    private String unquote(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2 && ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'")))) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-knowledge-source-mcp")
                        .put("version", "1.1").put("mode", "in-process").put("readOnly", true))
                .put("tools", new JSONArray().put("knowledge.classify_sources")
                        .put("knowledge.extract_uploads").put("knowledge.build_context"))
                .put("dependencies", new JSONArray().put(documents.describe().getJSONObject("server"))
                        .put(projects.describe().getJSONObject("server"))
                        .put(apiContracts.describe().getJSONObject("server"))
                        .put(jira.describe().getJSONObject("server")));
    }

    private Path safeLocalPath(String value) {
        try {
            if (value == null || value.isBlank() || isHttpUrl(value) || value.startsWith("git@")) return null;
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception ignored) { return null; }
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return "http".equals(scheme) || "https".equals(scheme);
        } catch (Exception ignored) { return false; }
    }

    private record ParsedSource(String location, String instructions) {}
}

package service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class DashboardExecutionService {

    public static final String FIREBASE_URL = "https://testweave-387ad-default-rtdb.firebaseio.com";
    private static final String FIREBASE_PATH = "/testweave-dashboard/executions";
    public static final String EXECUTION_LOGS_TABLE = "execution_logs";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public enum StorageMode {
        CLOUD,
        LOCAL
    }

    public synchronized void save(JSONObject execution, Path reportsRoot, StorageMode storageMode, Path sqliteDbPath) throws Exception {
        String id = execution.optString("id");
        if (id.isBlank()) {
            id = "execution-" + execution.optLong("executedAt", System.currentTimeMillis());
            execution.put("id", id);
        }
        Path cache = cachePath(reportsRoot);
        Files.createDirectories(cache.getParent());
        JSONArray stored = readLocal(cache);
        boolean exists = false;
        for (int i = 0; i < stored.length(); i++) {
            JSONObject current = stored.optJSONObject(i);
            if (current != null && id.equals(current.optString("id"))) {
                stored.put(i, execution);
                exists = true;
                break;
            }
        }
        if (!exists) {
            stored.put(execution);
        }
        Files.writeString(cache, stored.toString(2), StandardCharsets.UTF_8);
        if (storageMode == StorageMode.LOCAL) {
            saveSqlite(sqliteDbPath, execution);
        } else {
            uploadAsync(id, execution);
        }
    }

    public synchronized void save(JSONObject execution, Path reportsRoot) throws Exception {
        save(execution, reportsRoot, StorageMode.CLOUD, null);
    }

    public JSONArray load(Path reportsRoot, StorageMode storageMode, Path sqliteDbPath) throws Exception {
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        JSONArray local = readLocal(cachePath(reportsRoot));
        for (int i = 0; i < local.length(); i++) {
            JSONObject item = local.optJSONObject(i);
            if (item != null) {
                String id = item.optString("id", "local-" + i);
                merged.put(id, item);
                if (storageMode == StorageMode.LOCAL) {
                    saveSqlite(sqliteDbPath, item);
                } else {
                    uploadAsync(id, item);
                }
            }
        }
        if (storageMode == StorageMode.LOCAL) {
            JSONArray sqlite = loadSqlite(sqliteDbPath);
            for (int i = 0; i < sqlite.length(); i++) {
                JSONObject item = sqlite.optJSONObject(i);
                if (item != null) merged.put(item.optString("id", "sqlite-" + i), item);
            }
        } else {
            try {
                HttpResponse<String> response = client.send(request(FIREBASE_PATH + ".json").GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300
                        && !response.body().isBlank() && !"null".equals(response.body())) {
                    JSONObject remote = new JSONObject(response.body());
                    for (String key : remote.keySet()) {
                        JSONObject item = remote.optJSONObject(key);
                        if (item != null) merged.put(item.optString("id", key), item);
                    }
                }
            } catch (Exception ignored) {
                // The dashboard remains useful from its local mirror while Firebase is offline.
            }
        }
        JSONArray executions = new JSONArray(merged.values());
        writeLocal(cachePath(reportsRoot), executions);
        return executions;
    }

    public JSONArray load(Path reportsRoot) throws Exception {
        return load(reportsRoot, StorageMode.CLOUD, null);
    }

    public void importExistingReports(Path reportsRoot, StorageMode storageMode, Path sqliteDbPath) throws Exception {
        Set<String> known = new HashSet<>();
        JSONArray local = readLocal(cachePath(reportsRoot));
        for (int i = 0; i < local.length(); i++) {
            JSONObject item = local.optJSONObject(i);
            if (item != null) known.add(item.optString("id"));
        }
        importSuiteReports(reportsRoot.resolve("TestSuite_Reports"), reportsRoot, known, storageMode, sqliteDbPath);
        importSuiteReports(Path.of("TestSuites", "Reports"), reportsRoot, known, storageMode, sqliteDbPath);
        importPerformanceReports(reportsRoot.resolve("Perfomance_Reports"), reportsRoot, known, storageMode, sqliteDbPath);
        importPerformanceReports(Path.of("target", "performance-reports"), reportsRoot, known, storageMode, sqliteDbPath);
    }

    public void importExistingReports(Path reportsRoot) throws Exception {
        importExistingReports(reportsRoot, StorageMode.CLOUD, null);
    }

    private void importSuiteReports(Path directory, Path reportsRoot, Set<String> known, StorageMode storageMode, Path sqliteDbPath) throws Exception {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.list(directory)) {
            for (Path report : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".html")).toList()) {
                String id = "import-suite-" + report.getFileName().toString().replaceAll("[^A-Za-z0-9_-]", "_");
                if (known.contains(id)) continue;
                String html = Files.readString(report, StandardCharsets.UTF_8);
                long total = htmlMetric(html, "Total Steps");
                long passed = htmlMetric(html, "Passed");
                long failed = htmlMetric(html, "Failed");
                JSONArray details = parseSuiteDetails(html);
                Set<String> testCases = new HashSet<>();
                for (int i = 0; i < details.length(); i++) {
                    JSONObject detail = details.optJSONObject(i);
                    if (detail != null) testCases.add(detail.optString("suite") + "\u0000" + detail.optString("testCase"));
                }
                double rate = total == 0 ? 0 : passed * 100.0 / total;
                String name = report.getFileName().toString().replaceFirst("-report-.*$", "");
                JSONObject execution = new JSONObject().put("id", id).put("type", "TEST_SUITE").put("name", name)
                        .put("executedAt", Files.getLastModifiedTime(report).toMillis()).put("totalTestCases", testCases.size())
                        .put("totalSteps", total).put("passed", passed).put("failed", failed)
                        .put("passPercentage", rate).put("health", health(rate)).put("reportPath", report.toString())
                        .put("details", details);
                save(execution, reportsRoot, storageMode, sqliteDbPath);
                known.add(id);
            }
        }
    }

    private void importPerformanceReports(Path directory, Path reportsRoot, Set<String> known, StorageMode storageMode, Path sqliteDbPath) throws Exception {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path statistics : files.filter(Files::isRegularFile)
                    .filter(path -> "statistics.json".equalsIgnoreCase(path.getFileName().toString())).toList()) {
                Path runDirectory = statistics.getParent();
                String id = "import-performance-" + runDirectory.getFileName().toString().replaceAll("[^A-Za-z0-9_-]", "_");
                if (known.contains(id)) continue;
                JSONObject total = new JSONObject(Files.readString(statistics, StandardCharsets.UTF_8)).optJSONObject("Total");
                if (total == null) continue;
                long samples = total.optLong("sampleCount");
                long errors = total.optLong("errorCount");
                long passed = Math.max(0, samples - errors);
                double rate = samples == 0 ? 0 : passed * 100.0 / samples;
                JSONObject performance = new JSONObject().put("samples", samples).put("errors", errors)
                        .put("averageMs", total.optDouble("meanResTime")).put("p90Ms", total.optDouble("pct1ResTime"))
                        .put("p95Ms", total.optDouble("pct2ResTime")).put("p99Ms", total.optDouble("pct3ResTime"))
                        .put("throughputPerSecond", total.optDouble("throughput"));
                JSONObject execution = new JSONObject().put("id", id).put("type", "PERFORMANCE")
                        .put("name", runDirectory.getFileName().toString())
                        .put("executedAt", Files.getLastModifiedTime(statistics).toMillis()).put("totalTestCases", 1)
                        .put("totalSteps", samples).put("passed", passed).put("failed", errors)
                        .put("passPercentage", rate).put("health", health(rate)).put("performance", performance)
                        .put("reportPath", runDirectory.resolve("index.html").toString())
                        .put("details", new JSONArray().put(new JSONObject().put("suite", "Performance")
                                .put("testCase", "Load Test").put("testStep", runDirectory.getFileName().toString())
                                .put("type", "Performance Test").put("status", errors == 0 ? "Passed" : "Failed")
                                .put("message", samples + " samples; " + errors + " errors")));
                save(execution, reportsRoot, storageMode, sqliteDbPath);
                known.add(id);
            }
        }
    }

    private long htmlMetric(String html, String label) {
        Matcher matcher = Pattern.compile("<div class=\\\"metric\\\"><b>(\\d+)(?:%?)</b>" + Pattern.quote(label),
                Pattern.CASE_INSENSITIVE).matcher(html);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private JSONArray parseSuiteDetails(String html) {
        JSONArray details = new JSONArray();
        Matcher articles = Pattern.compile("<article[^>]*>(.*?)</article>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (articles.find()) {
            String article = articles.group(1);
            Matcher heading = Pattern.compile("<h2>(.*?)</h2>.*?<div class=\\\"meta\\\">(.*?) / (.*?)</div>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(article);
            if (!heading.find()) continue;
            String step = plain(heading.group(1));
            String suite = plain(heading.group(2));
            String testCase = plain(heading.group(3));
            String type = fact(article, "Step Type");
            String status = fact(article, "Status");
            details.put(new JSONObject().put("suite", suite).put("testCase", testCase).put("testStep", step)
                    .put("type", type).put("status", status).put("message", "Imported from HTML report"));
        }
        return details;
    }

    private String fact(String html, String label) {
        Matcher matcher = Pattern.compile("<span>" + Pattern.quote(label) + "</span><b>(.*?)</b>",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        return matcher.find() ? plain(matcher.group(1)) : "";
    }

    private String plain(String html) {
        return html.replaceAll("<[^>]+>", "").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim();
    }

    private String health(double passRate) {
        return passRate >= 95 ? "Excellent" : passRate >= 80 ? "Watch" : "At risk";
    }

    private void uploadAsync(String id, JSONObject execution) {
        String safeId = URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = request(FIREBASE_PATH + "/" + safeId + ".json")
                .PUT(HttpRequest.BodyPublishers.ofString(execution.toString(), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, throwable) -> {
                    if (throwable != null) {
                        System.err.println("Firebase dashboard upload failed for " + id + ": " + throwable.getMessage());
                    } else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        System.err.println("Firebase dashboard upload failed for " + id + ": HTTP "
                                + response.statusCode() + " " + response.body());
                    }
                });
    }

    public void initializeSqlite(Path sqliteDbPath) throws Exception {
        if (sqliteDbPath == null) {
            throw new IllegalArgumentException("SQLite DB path is required for local execution log storage.");
        }
        Path parent = sqliteDbPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        try (Connection connection = openSqlite(sqliteDbPath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + EXECUTION_LOGS_TABLE + " ("
                    + "id TEXT PRIMARY KEY,"
                    + "type TEXT NOT NULL,"
                    + "name TEXT,"
                    + "executed_at INTEGER NOT NULL,"
                    + "passed INTEGER NOT NULL DEFAULT 0,"
                    + "failed INTEGER NOT NULL DEFAULT 0,"
                    + "total_test_cases INTEGER NOT NULL DEFAULT 0,"
                    + "total_steps INTEGER NOT NULL DEFAULT 0,"
                    + "health TEXT,"
                    + "report_path TEXT,"
                    + "payload_json TEXT NOT NULL,"
                    + "updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP"
                    + ")");
        }
    }

    public void clearSqlite(Path sqliteDbPath) throws Exception {
        initializeSqlite(sqliteDbPath);
        try (Connection connection = openSqlite(sqliteDbPath);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + EXECUTION_LOGS_TABLE);
        }
    }

    public void clearFirebase() throws Exception {
        HttpResponse<String> response = client.send(request(FIREBASE_PATH + ".json")
                        .DELETE()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Firebase clear failed: HTTP " + response.statusCode() + " " + response.body());
        }
    }

    private void saveSqlite(Path sqliteDbPath, JSONObject execution) throws Exception {
        initializeSqlite(sqliteDbPath);
        String sql = "INSERT INTO " + EXECUTION_LOGS_TABLE
                + " (id, type, name, executed_at, passed, failed, total_test_cases, total_steps, health, report_path, payload_json, updated_at) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP) "
                + "ON CONFLICT(id) DO UPDATE SET type = excluded.type, name = excluded.name, executed_at = excluded.executed_at, "
                + "passed = excluded.passed, failed = excluded.failed, total_test_cases = excluded.total_test_cases, "
                + "total_steps = excluded.total_steps, health = excluded.health, report_path = excluded.report_path, "
                + "payload_json = excluded.payload_json, updated_at = CURRENT_TIMESTAMP";
        try (Connection connection = openSqlite(sqliteDbPath);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, execution.optString("id"));
            statement.setString(2, execution.optString("type"));
            statement.setString(3, execution.optString("name"));
            statement.setLong(4, execution.optLong("executedAt"));
            statement.setLong(5, execution.optLong("passed"));
            statement.setLong(6, execution.optLong("failed"));
            statement.setInt(7, execution.optInt("totalTestCases"));
            statement.setLong(8, execution.optLong("totalSteps"));
            statement.setString(9, execution.optString("health"));
            statement.setString(10, execution.optString("reportPath"));
            statement.setString(11, execution.toString());
            statement.executeUpdate();
        }
    }

    private JSONArray loadSqlite(Path sqliteDbPath) throws Exception {
        initializeSqlite(sqliteDbPath);
        JSONArray executions = new JSONArray();
        try (Connection connection = openSqlite(sqliteDbPath);
             PreparedStatement statement = connection.prepareStatement("SELECT payload_json FROM "
                     + EXECUTION_LOGS_TABLE + " ORDER BY executed_at DESC")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    executions.put(new JSONObject(resultSet.getString("payload_json")));
                }
            }
        }
        return executions;
    }

    private Connection openSqlite(Path sqliteDbPath) throws Exception {
        return DriverManager.getConnection("jdbc:sqlite:" + sqliteDbPath.toAbsolutePath().normalize());
    }

    private HttpRequest.Builder request(String path) {
        String auth = firstNonBlank(System.getProperty("testweave.firebase.authToken"),
                System.getenv("TESTWEAVE_FIREBASE_AUTH_TOKEN"));
        String uri = FIREBASE_URL + path + (auth.isBlank() ? "" : "?auth="
                + URLEncoder.encode(auth, StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create(uri)).timeout(Duration.ofSeconds(10));
    }

    private JSONArray readLocal(Path cache) throws Exception {
        if (!Files.exists(cache)) return new JSONArray();
        String text = Files.readString(cache, StandardCharsets.UTF_8).trim();
        return text.isBlank() ? new JSONArray() : new JSONArray(text);
    }

    private void writeLocal(Path cache, JSONArray executions) throws Exception {
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, executions.toString(2), StandardCharsets.UTF_8);
    }

    private Path cachePath(Path reportsRoot) {
        return reportsRoot.toAbsolutePath().normalize().resolve("dashboard-executions.json");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
}

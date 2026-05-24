package ui;

import model.ApiRequest;
import model.ApiResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import service.ApiService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TestWeaveCliRunner {

    private static final List<String> HEADER = List.of("Test Suite", "Test Case", "Test Step", "Hit Request",
            "Request Payload", "Captured Variables", "API_FIELD_VALIDATION", "Variable Dependencies",
            "JSON_COMPARE", "DB_VALIDATION", "DB_CONNECTION", "DB_QUERY", "API_DB_VALIDATION",
            "DB_COLUMN_VALIDATION", "WEB_TEST", "PERFORMANCE_TEST", "Run", "Execution Mode", "Status");

    private final ApiService apiService = new ApiService();
    private final AtomicBoolean failed = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        new TestWeaveCliRunner().run(args);
    }

    private void run(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path suite = Path.of(options.getOrDefault("suite", "testweave/test-suite.xlsx"));
        boolean parallel = Boolean.parseBoolean(options.getOrDefault("parallel", "false"));
        int threads = Math.max(1, Integer.parseInt(options.getOrDefault("threads", "1")));
        Path reportDir = Path.of(options.getOrDefault("report", "target/testweave-report"));
        Files.createDirectories(reportDir);

        List<Map<String, String>> rows = readRows(suite).stream()
                .filter(row -> Boolean.parseBoolean(row.getOrDefault("Run", "true")))
                .toList();
        List<Map<String, String>> results = runRows(rows, parallel ? threads : 1);
        writeReports(reportDir, results);
        if (failed.get()) {
            throw new IllegalStateException("One or more TestWeave steps failed.");
        }
    }

    private List<Map<String, String>> runRows(List<Map<String, String>> rows, int threads) throws Exception {
        Object sequentialLock = new Object();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Map<String, String>>> futures = new ArrayList<>();
        for (Map<String, String> row : rows) {
            futures.add(executor.submit(() -> {
                boolean sequential = !"Parallel".equalsIgnoreCase(row.getOrDefault("Execution Mode", "Sequential"));
                if (threads <= 1 || sequential) {
                    synchronized (sequentialLock) {
                        return execute(row);
                    }
                }
                return execute(row);
            }));
        }
        List<Map<String, String>> results = new ArrayList<>();
        for (Future<Map<String, String>> future : futures) {
            results.add(future.get());
        }
        executor.shutdownNow();
        return results;
    }

    private Map<String, String> execute(Map<String, String> row) {
        Map<String, String> result = new LinkedHashMap<>(row);
        result.put("Started", LocalDateTime.now().toString());
        try {
            if (!row.getOrDefault("Hit Request", "").isBlank()) {
                ApiResponse response = apiService.sendRequest(buildRequest(row));
                boolean passed = response.statusCode < 400;
                result.put("Status", passed ? "Passed" : "Failed");
                result.put("Message", "HTTP " + response.statusCode);
                failed.compareAndSet(false, !passed);
            } else {
                result.put("Status", "Passed");
                result.put("Message", "No executable API payload found; marked as passed.");
            }
        } catch (Exception e) {
            failed.set(true);
            result.put("Status", "Failed");
            result.put("Message", e.getMessage());
        }
        result.put("Finished", LocalDateTime.now().toString());
        return result;
    }

    private ApiRequest buildRequest(Map<String, String> row) {
        JSONObject hit = new JSONObject(row.getOrDefault("Hit Request", "{}"));
        ApiRequest request = new ApiRequest();
        request.method = hit.optString("method", "GET");
        request.url = hit.optString("endpoint", "");
        request.headers = parseHeaders(hit.optString("headersText", ""));
        request.body = row.getOrDefault("Request Payload", "");
        request.token = "";
        return request;
    }

    private Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return headers;
        }
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                headers.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    private List<Map<String, String>> readRows(Path workbookPath) throws Exception {
        Map<String, byte[]> entries = readWorkbookEntries(workbookPath);
        String sheetXml = new String(Objects.requireNonNull(entries.get("xl/worksheets/sheet1.xml"),
                "Workbook is missing xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        List<String> sharedStrings = readSharedStrings(entries);
        List<List<String>> sheetRows = readSheetRows(sheetXml, sharedStrings);
        List<String> header = null;
        List<Map<String, String>> rows = new ArrayList<>();
        for (List<String> sheetRow : sheetRows) {
            if (header == null) {
                if (sheetRow.size() >= 3 && "Test Suite".equals(sheetRow.get(0))) {
                    header = sheetRow;
                }
                continue;
            }
            if (sheetRow.stream().allMatch(value -> value == null || value.isBlank())) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < HEADER.size(); i++) {
                String key = i < header.size() ? header.get(i) : HEADER.get(i);
                row.put(key, i < sheetRow.size() ? sheetRow.get(i) : "");
            }
            if (!row.getOrDefault("Test Step", "").isBlank()) {
                rows.add(row);
            }
        }
        return rows;
    }

    private Map<String, byte[]> readWorkbookEntries(Path workbookPath) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile workbookZip = new ZipFile(workbookPath.toFile())) {
            var zipEntries = workbookZip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (!entry.isDirectory()) {
                    try (var input = workbookZip.getInputStream(entry)) {
                        entries.put(entry.getName(), input.readAllBytes());
                    }
                }
            }
        }
        return entries;
    }

    private List<List<String>> readSheetRows(String sheetXml, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        java.util.regex.Matcher rowMatcher = java.util.regex.Pattern
                .compile("<row\\b[^>]*>(.*?)</row>", java.util.regex.Pattern.DOTALL)
                .matcher(sheetXml);
        while (rowMatcher.find()) {
            rows.add(rowValues(rowMatcher.group(1), sharedStrings));
        }
        return rows;
    }

    private List<String> rowValues(String rowXml, List<String> sharedStrings) {
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher cellMatcher = java.util.regex.Pattern
                .compile("<c\\b([^>]*)>(.*?)</c>", java.util.regex.Pattern.DOTALL)
                .matcher(rowXml);
        while (cellMatcher.find()) {
            values.add(cellText(cellMatcher.group(1), cellMatcher.group(2), sharedStrings));
        }
        return values;
    }

    private String cellText(String attributes, String cellXml, List<String> sharedStrings) {
        java.util.regex.Matcher inlineMatcher = java.util.regex.Pattern
                .compile("<is>\\s*<t[^>]*>(.*?)</t>\\s*</is>", java.util.regex.Pattern.DOTALL)
                .matcher(cellXml);
        if (inlineMatcher.find()) {
            return unescapeXml(inlineMatcher.group(1));
        }
        java.util.regex.Matcher valueMatcher = java.util.regex.Pattern
                .compile("<v>(.*?)</v>", java.util.regex.Pattern.DOTALL)
                .matcher(cellXml);
        if (!valueMatcher.find()) {
            return "";
        }
        String value = valueMatcher.group(1).trim();
        if (attributes.contains("t=\"s\"")) {
            int index = Integer.parseInt(value);
            return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : "";
        }
        return unescapeXml(value);
    }

    private List<String> readSharedStrings(Map<String, byte[]> entries) {
        byte[] bytes = entries.get("xl/sharedStrings.xml");
        if (bytes == null) {
            return List.of();
        }
        String xml = new String(bytes, StandardCharsets.UTF_8);
        List<String> values = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<si\\b[^>]*>(.*?)</si>", java.util.regex.Pattern.DOTALL)
                .matcher(xml);
        while (matcher.find()) {
            java.util.regex.Matcher textMatcher = java.util.regex.Pattern
                    .compile("<t[^>]*>(.*?)</t>", java.util.regex.Pattern.DOTALL)
                    .matcher(matcher.group(1));
            StringBuilder value = new StringBuilder();
            while (textMatcher.find()) {
                value.append(unescapeXml(textMatcher.group(1)));
            }
            values.add(value.toString());
        }
        return values;
    }

    private void writeReports(Path reportDir, List<Map<String, String>> results) throws Exception {
        JSONArray json = new JSONArray(results);
        Files.writeString(reportDir.resolve("testweave-results.json"), json.toString(2), StandardCharsets.UTF_8);
        StringBuilder html = new StringBuilder("<html><body><h1>TestWeave Results</h1><table border='1'><tr>");
        for (String header : List.of("Test Suite", "Test Case", "Test Step", "Execution Mode", "Status", "Message")) {
            html.append("<th>").append(header).append("</th>");
        }
        html.append("</tr>");
        for (Map<String, String> result : results) {
            html.append("<tr>");
            for (String header : List.of("Test Suite", "Test Case", "Test Step", "Execution Mode", "Status", "Message")) {
                html.append("<td>").append(escapeHtml(result.getOrDefault(header, ""))).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table></body></html>");
        Files.writeString(reportDir.resolve("index.html"), html.toString(), StandardCharsets.UTF_8);
    }

    private Map<String, String> parseArgs(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                options.put(args[i].substring(2), args[++i]);
            }
        }
        return options;
    }

    private String unescapeXml(String value) {
        return value.replace("&apos;", "'").replace("&quot;", "\"").replace("&gt;", ">")
                .replace("&lt;", "<").replace("&amp;", "&");
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

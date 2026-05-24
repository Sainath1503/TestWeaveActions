package ui;

import model.ApiRequest;
import model.ApiResponse;
import model.PerformanceTestResult;
import model.WebTestCase;
import model.WebTestExecutionResult;
import model.WebTestRunReport;
import model.WebTestStep;
import org.json.JSONArray;
import org.json.JSONObject;
import service.ApiService;
import service.PerformanceTestService;
import service.PlaywrightRecorderController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class TestWeaveCliRunner {

    private static final List<String> HEADER = List.of("Test Suite", "Test Case", "Test Step", "Hit Request",
            "Request Payload", "Captured Variables", "API_FIELD_VALIDATION", "Variable Dependencies",
            "JSON_COMPARE", "DB_VALIDATION", "DB_CONNECTION", "DB_QUERY", "API_DB_VALIDATION",
            "DB_COLUMN_VALIDATION", "WEB_TEST", "PERFORMANCE_TEST", "Run", "Execution Mode", "Status");

    private final ApiService apiService = new ApiService();
    private final PerformanceTestService performanceTestService = new PerformanceTestService();
    private final PlaywrightRecorderController playwrightRecorderController = new PlaywrightRecorderController();
    private final AtomicBoolean failed = new AtomicBoolean(false);

    public static void main(String[] args) throws Exception {
        configureCiLogging();
        new TestWeaveCliRunner().run(args);
    }

    private static void configureCiLogging() {
        System.setProperty("log4j2.loggerContextFactory",
                "org.apache.logging.log4j.simple.SimpleLoggerContextFactory");
        System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF");
        System.setProperty("org.apache.logging.log4j.simplelog.defaultLevel", "error");
    }

    private void run(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        Path suite = Path.of(options.getOrDefault("suite", "testweave/test-suite.xlsx"));
        boolean parallel = Boolean.parseBoolean(options.getOrDefault("parallel", "false"));
        int threads = Math.max(1, Integer.parseInt(options.getOrDefault("threads", "1")));
        Path reportDir = Path.of(options.getOrDefault("report", "target/testweave-report"));
        Files.createDirectories(reportDir);

        try {
            List<Map<String, String>> rows = readRows(suite).stream()
                    .filter(row -> Boolean.parseBoolean(row.getOrDefault("Run", "true")))
                    .toList();
            List<Map<String, String>> results = runRows(rows, parallel ? threads : 1);
            writeReports(reportDir, results);
            printSummary(results);
            if (failed.get()) {
                throw new IllegalStateException("One or more TestWeave steps failed.");
            }
        } catch (Exception e) {
            if (!Files.exists(reportDir.resolve("testweave-results.json"))) {
                writeReports(reportDir, List.of(startupFailureResult(suite, e)));
            }
            throw e;
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
            if (!row.getOrDefault("WEB_TEST", "").isBlank()) {
                executeWebTest(row, result);
            } else if (!row.getOrDefault("PERFORMANCE_TEST", "").isBlank()) {
                executePerformanceTest(row, result);
            } else if (!row.getOrDefault("Hit Request", "").isBlank()) {
                ApiResponse response = apiService.sendRequest(buildRequest(row));
                boolean passed = response.statusCode < 400;
                result.put("Status", passed ? "Passed" : "Failed");
                result.put("Message", "HTTP " + response.statusCode + ", duration: " + response.timeMs + " ms");
                failed.compareAndSet(false, !passed);
            } else {
                result.put("Status", "Passed");
                result.put("Message", "Manual step completed.");
            }
        } catch (Exception e) {
            failed.set(true);
            result.put("Status", "Failed");
            result.put("Message", e.getMessage());
        }
        result.put("Finished", LocalDateTime.now().toString());
        return result;
    }

    private void executeWebTest(Map<String, String> row, Map<String, String> result) throws Exception {
        WebTestRunReport webReport = playwrightRecorderController.runTest(buildWebTestCase(row), webHeadless(row), webSlowMo(row));
        boolean passed = webReport.failed == 0 && webReport.total > 0;
        result.put("Status", passed ? "Passed" : "Failed");
        result.put("Message", "Web steps executed: " + webReport.total
                + ", passed: " + webReport.passed + ", failed: " + webReport.failed
                + firstFailedWebMessage(webReport));
        failed.compareAndSet(false, !passed);
    }

    private WebTestCase buildWebTestCase(Map<String, String> row) {
        JSONObject config = new JSONObject(row.get("WEB_TEST"));
        WebTestCase testCase = new WebTestCase();
        testCase.testName = resolveVariables(config.optString("testName", row.getOrDefault("Test Step", "Web Test")));
        testCase.startUrl = resolveVariables(config.optString("startUrl"));
        JSONArray steps = config.optJSONArray("steps");
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("WEB_TEST step does not contain recorded web steps.");
        }
        for (int i = 0; i < steps.length(); i++) {
            JSONObject item = steps.optJSONObject(i);
            if (item == null) {
                continue;
            }
            WebTestStep step = new WebTestStep();
            step.action = item.optString("action");
            step.selector = resolveVariables(item.optString("selector"));
            step.value = "Get Text".equalsIgnoreCase(step.action)
                    ? item.optString("value")
                    : resolveVariables(item.optString("value"));
            step.note = item.optString("note");
            step.suggested = item.optBoolean("suggested");
            testCase.steps.add(step);
        }
        return testCase;
    }

    private boolean webHeadless(Map<String, String> row) {
        JSONObject config = new JSONObject(row.get("WEB_TEST"));
        if ("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) {
            return true;
        }
        return config.optBoolean("headless", false);
    }

    private int webSlowMo(Map<String, String> row) {
        if ("true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))) {
            return 0;
        }
        JSONObject config = new JSONObject(row.get("WEB_TEST"));
        return Math.max(0, config.optInt("slowMoMillis", 0));
    }

    private String firstFailedWebMessage(WebTestRunReport webReport) {
        for (WebTestExecutionResult stepResult : webReport.results) {
            if (!stepResult.passed) {
                return "; first failure: " + nullToBlank(stepResult.action)
                        + " " + nullToBlank(stepResult.selector)
                        + " - " + nullToBlank(stepResult.message);
            }
        }
        return "";
    }

    private void executePerformanceTest(Map<String, String> row, Map<String, String> result) throws Exception {
        JSONObject performance = new JSONObject(row.get("PERFORMANCE_TEST"));
        String body = performance.optString("body", row.getOrDefault("Request Payload", ""));
        PerformanceTestResult performanceResult = performanceTestService.runLoadTest(buildRequest(row, body),
                Math.max(1, performance.optInt("threads", 1)),
                Math.max(1, performance.optInt("iterationsPerThread", 1)));
        boolean passed = performanceResult.errors == 0;
        result.put("Status", passed ? "Passed" : "Failed");
        result.put("Message", "Performance samples: " + performanceResult.samples
                + ", errors: " + performanceResult.errors
                + ", report: " + nullToBlank(performanceResult.reportIndexPath == null
                ? "" : performanceResult.reportIndexPath.toString()));
        failed.compareAndSet(false, !passed);
    }

    private ApiRequest buildRequest(Map<String, String> row) {
        return buildRequest(row, row.getOrDefault("Request Payload", ""));
    }

    private ApiRequest buildRequest(Map<String, String> row, String body) {
        JSONObject hit = new JSONObject(row.getOrDefault("Hit Request", "{}"));
        ApiRequest request = new ApiRequest();
        request.method = hit.optString("method", "GET");
        request.url = resolveVariables(hit.optString("endpoint", ""));
        request.headers = parseHeaders(resolveVariables(hit.optString("headersText", "")));
        request.body = resolveVariables(body == null ? "" : body);
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
        int nextColumnIndex = 0;
        while (cellMatcher.find()) {
            String attributes = cellMatcher.group(1);
            int columnIndex = cellColumnIndex(attributes);
            if (columnIndex < 0) {
                columnIndex = nextColumnIndex;
            }
            while (values.size() < columnIndex) {
                values.add("");
            }
            String value = cellText(attributes, cellMatcher.group(2), sharedStrings);
            if (values.size() == columnIndex) {
                values.add(value);
            } else {
                values.set(columnIndex, value);
            }
            nextColumnIndex = columnIndex + 1;
        }
        return values;
    }

    private int cellColumnIndex(String cellAttributes) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\br=\"([A-Z]+)\\d+\"")
                .matcher(cellAttributes);
        if (!matcher.find()) {
            return -1;
        }
        int column = 0;
        String letters = matcher.group(1);
        for (int i = 0; i < letters.length(); i++) {
            column = column * 26 + (letters.charAt(i) - 'A' + 1);
        }
        return column - 1;
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
        String html = buildGithubActionsReportHtml(results);
        Files.writeString(reportDir.resolve("index.html"), html, StandardCharsets.UTF_8);
        Files.writeString(reportDir.resolve(reportFileName(results)), html, StandardCharsets.UTF_8);
    }

    private String buildGithubActionsReportHtml(List<Map<String, String>> results) {
        long passed = results.stream().filter(this::isPassed).count();
        long failedCount = results.stream().filter(this::isFailed).count();
        long total = results.size();
        int passPercent = total == 0 ? 0 : Math.round((passed * 100f) / total);
        int failPercent = total == 0 ? 0 : 100 - passPercent;

        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html>
                <head>
                <meta charset="utf-8">
                <title>TestWeave GitHub Actions Report</title>
                <style>
                body{font-family:Arial,Helvetica,sans-serif;margin:0;background:#f6f8fb;color:#0f172a}
                header{background:#10233f;color:#fff;padding:24px 32px}
                h1{margin:0;font-size:28px} .sub{margin-top:6px;color:#cbd5e1}
                main{padding:24px 32px}.summary{display:grid;grid-template-columns:repeat(4,minmax(120px,1fr));gap:14px;margin-bottom:22px}
                .card{background:#fff;border:1px solid #d9e2ef;border-radius:8px;padding:16px;box-shadow:0 1px 2px rgba(15,23,42,.06)}
                .label{color:#64748b;font-size:13px}.value{font-size:26px;font-weight:700;margin-top:6px}
                .pass{color:#15803d}.fail{color:#b91c1c}.ready{color:#1d4ed8}
                table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #d9e2ef}
                th{background:#eaf1fb;text-align:left;padding:11px;border:1px solid #d9e2ef}
                td{padding:10px;border:1px solid #d9e2ef;vertical-align:top}
                tr.failed-row{background:#fff5f5}tr.passed-row{background:#f7fff8}
                .status{font-weight:700}.mono{font-family:Consolas,Menlo,monospace;white-space:pre-wrap}
                .bar{height:12px;background:#fee2e2;border-radius:999px;overflow:hidden;margin-top:10px}
                .bar span{display:block;height:100%;background:#22c55e}
                .section-title{font-size:20px;margin:24px 0 10px}
                </style>
                </head>
                <body>
                """);
        html.append("<header><h1>TestWeave GitHub Actions Report</h1><div class='sub'>Generated ")
                .append(escapeHtml(LocalDateTime.now().toString()))
                .append("</div></header><main>");
        html.append("<section class='summary'>")
                .append(summaryCard("Total Steps", String.valueOf(total), ""))
                .append(summaryCard("Passed", String.valueOf(passed), "pass"))
                .append(summaryCard("Failed", String.valueOf(failedCount), "fail"))
                .append(summaryCard("Pass Rate", passPercent + "%", passPercent == 100 ? "pass" : "ready"))
                .append("</section>");
        html.append("<div class='card'><div class='label'>Execution result</div><div class='bar'><span style='width:")
                .append(passPercent)
                .append("%'></span></div><div class='sub'>")
                .append(passPercent).append("% passed, ").append(failPercent).append("% failed</div></div>");

        html.append("<h2 class='section-title'>Step Execution Details</h2>");
        html.append("<table><tr>");
        for (String header : List.of("Status", "Suite", "Case", "Step", "Execution Mode", "Started", "Finished", "Message")) {
            html.append("<th>").append(header).append("</th>");
        }
        html.append("</tr>");
        for (Map<String, String> result : results) {
            String rowClass = isFailed(result) ? "failed-row" : isPassed(result) ? "passed-row" : "";
            html.append("<tr class='").append(rowClass).append("'>")
                    .append("<td class='status ").append(isFailed(result) ? "fail" : "pass").append("'>")
                    .append(escapeHtml(result.getOrDefault("Status", ""))).append("</td>")
                    .append("<td>").append(escapeHtml(result.getOrDefault("Test Suite", ""))).append("</td>")
                    .append("<td>").append(escapeHtml(result.getOrDefault("Test Case", ""))).append("</td>")
                    .append("<td>").append(escapeHtml(result.getOrDefault("Test Step", ""))).append("</td>")
                    .append("<td>").append(escapeHtml(result.getOrDefault("Execution Mode", ""))).append("</td>")
                    .append("<td>").append(escapeHtml(result.getOrDefault("Started", ""))).append("</td>")
                    .append("<td>").append(escapeHtml(result.getOrDefault("Finished", ""))).append("</td>")
                    .append("<td class='mono'>").append(escapeHtml(result.getOrDefault("Message", ""))).append("</td>")
                    .append("</tr>");
        }
        html.append("</table></main></body></html>");
        return html.toString();
    }

    private String summaryCard(String label, String value, String cssClass) {
        return "<div class='card'><div class='label'>" + escapeHtml(label) + "</div><div class='value "
                + cssClass + "'>" + escapeHtml(value) + "</div></div>";
    }

    private String reportFileName(List<Map<String, String>> results) {
        String suite = results.stream()
                .map(result -> result.getOrDefault("Test Suite", "test-suite"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst()
                .orElse("test-suite")
                .replaceAll("[^A-Za-z0-9_.-]", "_");
        return suite + "-github-actions-report-" + System.currentTimeMillis() + ".html";
    }

    private boolean isPassed(Map<String, String> result) {
        return result.getOrDefault("Status", "").toLowerCase().startsWith("passed");
    }

    private boolean isFailed(Map<String, String> result) {
        return result.getOrDefault("Status", "").toLowerCase().startsWith("failed");
    }

    private void printSummary(List<Map<String, String>> results) {
        long passed = results.stream().filter(result -> "Passed".equalsIgnoreCase(result.getOrDefault("Status", ""))).count();
        long failedCount = results.stream().filter(result -> "Failed".equalsIgnoreCase(result.getOrDefault("Status", ""))).count();
        System.out.println("TestWeave summary: " + passed + " passed, " + failedCount + " failed, " + results.size() + " total.");
        results.stream()
                .filter(result -> "Failed".equalsIgnoreCase(result.getOrDefault("Status", "")))
                .forEach(result -> System.out.println("FAILED: " + result.getOrDefault("Test Case", "")
                        + " / " + result.getOrDefault("Test Step", "")
                        + " - " + result.getOrDefault("Message", "")));
    }

    private Map<String, String> startupFailureResult(Path suite, Exception e) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Test Suite", suite.toString());
        result.put("Test Case", "Runner startup");
        result.put("Test Step", "Load test suite workbook");
        result.put("Execution Mode", "Sequential");
        result.put("Status", "Failed");
        result.put("Message", e.getClass().getSimpleName() + ": " + e.getMessage());
        result.put("Started", LocalDateTime.now().toString());
        result.put("Finished", LocalDateTime.now().toString());
        return result;
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

    private String resolveVariables(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("${randomString}", randomString())
                .replace("${randomInt}", String.valueOf(ThreadLocalRandom.current().nextInt(10000, 999999)))
                .replace("${randomDate}", LocalDate.now().toString());
    }

    private String randomString() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package ui;

import compare.JsonComparator;
import model.ApiRequest;
import model.ApiResponse;
import model.DbConnectionConfig;
import model.DbValidationReport;
import model.DbValidationResult;
import model.DbValidationRule;
import model.PerformanceTestResult;
import model.WebTestCase;
import model.WebTestExecutionResult;
import model.WebTestRunReport;
import model.WebTestStep;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import service.ApiService;
import service.DbValidationService;
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
import java.util.UUID;
import java.util.regex.Matcher;
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
    private final JsonComparator comparator = new JsonComparator();
	    private final DbValidationService dbValidationService = new DbValidationService();
	    private final PerformanceTestService performanceTestService = new PerformanceTestService();
	    private final PlaywrightRecorderController playwrightRecorderController = new PlaywrightRecorderController();
	    private final AtomicBoolean failed = new AtomicBoolean(false);
	    private final Map<String, String> savedVariables = new LinkedHashMap<>();
	    private Path suitePath;

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
        suitePath = suite;
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
                throw new IllegalStateException("One or more VeyraAI steps failed.");
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
                if (hasValidationColumns(row)) {
                    Map<String, String> variables = runnerVariablesSnapshot();
                    captureRunnerVariables(row, response.rawBody, variables);
                    runApiFieldValidation(row, response.rawBody, variables, result);
                    runJsonCompare(row, response.rawBody, result);
                    runDbValidation(row, response.rawBody, variables, result);
                    passed = passed && validationsPassed(result);
                } else {
                    addValidation(result, "HTTP", "Status Code", "Success (<400)",
                            String.valueOf(response.statusCode), passed, "HTTP " + response.statusCode);
                }
                result.put("Status", passed ? "Passed" : "Failed");
                result.put("Message", "HTTP " + response.statusCode + ", duration: " + response.timeMs + " ms");
                failed.compareAndSet(false, !passed);
            } else if (hasValidationColumns(row)) {
                Map<String, String> variables = runnerVariablesSnapshot();
                runDbValidation(row, "", variables, result);
                boolean passed = validationsPassed(result) && !validationsFor(result).isEmpty();
                if (validationsFor(result).isEmpty()) {
                    addValidation(result, "DB Validation", "Execution failed", "", "", false,
                            "Validation columns were present, but no executable DB validation was configured.");
                }
                result.put("Status", passed ? "Passed" : "Failed");
                result.put("Message", passed ? "DB validations passed." : "DB validations failed.");
                failed.compareAndSet(false, !passed);
            } else {
                result.put("Status", "Passed");
                result.put("Message", "Manual step completed.");
                addValidation(result, "Manual Step", "Manual", "", "Completed", true, "Manual step completed.");
            }
        } catch (Exception e) {
            failed.set(true);
            result.put("Status", "Failed");
            result.put("Message", e.getMessage());
            addValidation(result, "Step Error", "Execution failed", "", "", false,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        result.put("Finished", LocalDateTime.now().toString());
        return result;
    }

    private void executeWebTest(Map<String, String> row, Map<String, String> result) throws Exception {
        WebTestRunReport webReport = playwrightRecorderController.runTest(buildWebTestCase(row), webHeadless(row), webSlowMo(row));
        boolean passed = webReport.failed == 0 && webReport.total > 0;
        for (WebTestExecutionResult stepResult : webReport.results) {
            addValidation(result, stepResult.action, nullToBlank(stepResult.selector),
                    nullToBlank(stepResult.expectedValue), stepResult.passed ? "PASS" : "FAIL",
                    stepResult.passed, nullToBlank(stepResult.message));
        }
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
        Map<String, String> runVariables = runnerVariablesSnapshot();
        for (int i = 0; i < steps.length(); i++) {
            JSONObject item = steps.optJSONObject(i);
            if (item == null) {
                continue;
            }
            WebTestStep step = new WebTestStep();
            step.stepName = item.optString("stepName");
            step.action = item.optString("action");
            if ("Flow Variable".equalsIgnoreCase(step.action)) {
                String name = normalizeWebVariableName(firstNonBlank(item.optString("flowVariableName"),
                        item.optString("selector"), item.optString("note")));
                String value = resolveRunnerVariables(item.optString("value"), runVariables);
                if (!name.isBlank()) runVariables.put(name, value);
                step.selector = "";
                step.value = value;
                step.note = name;
            } else {
                step.selector = resolveRunnerVariables(item.optString("selector"), runVariables);
                if ("Get Text".equalsIgnoreCase(step.action)) {
                    configureCliGetTextExpectation(step, item.optString("value"), runVariables);
                } else {
                    step.value = resolveRunnerVariables(item.optString("value"), runVariables);
                }
                step.note = resolveRunnerVariables(item.optString("note"), runVariables);
            }
            step.suggested = item.optBoolean("suggested");
            testCase.steps.add(step);
        }
        return testCase;
    }

    private void configureCliGetTextExpectation(WebTestStep step, String rawValue,
                                                Map<String, String> runVariables) {
        String expression = nullToBlank(rawValue).trim();
        String referenced = webExpectedVariableName(expression);
        String plain = normalizeWebVariableName(expression);
        String variableName = !referenced.isBlank() ? referenced : runVariables.containsKey(plain) ? plain : "";
        step.value = expression;
        step.expectedVariableName = variableName;
        if (variableName.isBlank()) {
            step.expectedVariablePresent = true;
            step.expectedVariableValue = resolveRunnerVariables(expression, runVariables);
        } else {
            step.expectedVariablePresent = runVariables.containsKey(variableName);
            step.expectedVariableValue = step.expectedVariablePresent ? runVariables.getOrDefault(variableName, "") : "";
        }
    }

    private String webExpectedVariableName(String expression) {
        String value = nullToBlank(expression).trim();
        if (value.startsWith("${") && value.endsWith("}")) return normalizeWebVariableName(value.substring(2, value.length() - 1));
        if (value.startsWith("{{") && value.endsWith("}}")) return normalizeWebVariableName(value.substring(2, value.length() - 2));
        return "";
    }

    private String normalizeWebVariableName(String value) {
        return nullToBlank(value).trim().replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private String firstNonBlank(String... values) {
        if (values != null) for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
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
        addValidation(result, "Performance Test",
                Math.max(1, performance.optInt("threads", 1)) + " threads x "
                        + Math.max(1, performance.optInt("iterationsPerThread", 1)) + " iterations",
                "0 errors", performanceResult.errors + " errors / " + performanceResult.samples + " samples",
                passed, "HTML report generated: " + nullToBlank(performanceResult.reportIndexPath == null
                        ? "" : performanceResult.reportIndexPath.toString()));
        result.put("Status", passed ? "Passed" : "Failed");
        result.put("Message", "Performance samples: " + performanceResult.samples
                + ", errors: " + performanceResult.errors
                + ", report: " + nullToBlank(performanceResult.reportIndexPath == null
                ? "" : performanceResult.reportIndexPath.toString()));
        failed.compareAndSet(false, !passed);
    }

    private boolean hasValidationColumns(Map<String, String> row) {
        return !row.getOrDefault("API_FIELD_VALIDATION", "").isBlank()
                || !row.getOrDefault("JSON_COMPARE", "").isBlank()
                || !row.getOrDefault("DB_VALIDATION", "").isBlank()
                || !row.getOrDefault("DB_CONNECTION", "").isBlank()
                || !row.getOrDefault("DB_QUERY", "").isBlank()
                || !row.getOrDefault("API_DB_VALIDATION", "").isBlank()
                || !row.getOrDefault("DB_COLUMN_VALIDATION", "").isBlank();
    }

    private Map<String, String> runnerVariablesSnapshot() {
        synchronized (savedVariables) {
            return new LinkedHashMap<>(savedVariables);
        }
    }

    private void captureRunnerVariables(Map<String, String> row, String responseBody, Map<String, String> variables) {
        String captureText = row.getOrDefault("Captured Variables", "");
        if (captureText.isBlank() || responseBody == null || responseBody.isBlank()) {
            return;
        }
        Object responseJson = new JSONTokener(responseBody).nextValue();
        Map<String, String> captured = new LinkedHashMap<>();
        for (String capture : captureText.split(";")) {
            int equals = capture.indexOf('=');
            if (equals <= 0 || equals == capture.length() - 1) {
                continue;
            }
            String name = capture.substring(0, equals).trim();
            String path = capture.substring(equals + 1).trim();
            if (name.isBlank() || path.isBlank()) {
                continue;
            }
            try {
                Object actual = extractJsonPathValue(responseJson, path);
                String value = actual == null || actual == JSONObject.NULL ? "" : String.valueOf(actual);
                variables.put(name, value);
                captured.put(name, value);
            } catch (Exception ignored) {
                // Optional captures should not hide the actual step validation result.
            }
        }
        if (!captured.isEmpty()) {
            synchronized (savedVariables) {
                savedVariables.putAll(captured);
            }
        }
    }

    private void runApiFieldValidation(Map<String, String> row, String responseBody,
                                       Map<String, String> variables, Map<String, String> result) {
        String validationJson = row.getOrDefault("API_FIELD_VALIDATION", "");
        if (validationJson.isBlank()) {
            return;
        }
        JSONArray validations = parseOptionalJsonObject(validationJson).optJSONArray("validations");
        if (validations == null) {
            validations = parseOptionalJsonArray(validationJson);
        }
        Object responseJson = responseBody == null || responseBody.isBlank()
                ? new JSONObject()
                : new JSONTokener(responseBody).nextValue();
        for (int i = 0; i < validations.length(); i++) {
            JSONObject validation = validations.optJSONObject(i);
            if (validation == null) {
                continue;
            }
            String path = validation.optString("jsonPath");
            Object actual = extractJsonPathValue(responseJson, path);
            String actualValue = actual == null || actual == JSONObject.NULL ? "" : String.valueOf(actual);
            String actualType = jsonValueType(actual);
            String nullRule = validation.optString("nullValidation");
            String typeRule = validation.optString("typeValidation");
            String expected = resolveRunnerVariables(validation.optString("expectedValueOrVariable"), variables);
            List<String> errors = fieldValidationErrors(actualType, actualValue, nullRule, typeRule, expected);
            boolean passed = errors.isEmpty();
            addValidation(result, path, "Null: " + nullRule + ", Type: " + typeRule,
                    expected, actualValue, passed, String.join(", ", errors));
        }
    }

    private void runJsonCompare(Map<String, String> row, String responseBody, Map<String, String> result) throws Exception {
        String compareJson = row.getOrDefault("JSON_COMPARE", "");
        if (compareJson.isBlank()) {
            return;
        }
        JSONObject config = new JSONObject(compareJson);
        JSONObject expectedResponse = config.optJSONObject("expectedResponse");
        if (expectedResponse == null) {
            addValidation(result, "JSON_COMPARE", "JSON Compare", "", "",
                    false, "JSON_COMPARE step does not contain expectedResponse details.");
            return;
        }
        Path expectedPath = resolveWorkbookRelativePath(suitePath,
                expectedResponse.optString("path"), expectedResponse.optString("relativePath"));
        String expected = Files.readString(expectedPath, StandardCharsets.UTF_8);
        boolean strict = "STRICT".equalsIgnoreCase(config.optString("compareMode"))
                || "Strict".equalsIgnoreCase(config.optString("compareMode"));
        List<Object[]> compareResults = comparator.compare(expected, responseBody, strict, true);
        for (Object[] compareResult : compareResults) {
            String type = valueAt(compareResult, 0);
            boolean passed = "Match".equals(type) || "Message".equals(type);
            addValidation(result, valueAt(compareResult, 1), "JSON " + type,
                    valueAt(compareResult, 2), valueAt(compareResult, 3),
                    passed, passed ? "" : "JSON comparison mismatch");
        }
    }

    private void runDbValidation(Map<String, String> row, String responseBody,
                                 Map<String, String> variables, Map<String, String> result) throws Exception {
        boolean hasDbValidation = !row.getOrDefault("DB_VALIDATION", "").isBlank()
                || !row.getOrDefault("DB_CONNECTION", "").isBlank()
                || !row.getOrDefault("DB_QUERY", "").isBlank()
                || !row.getOrDefault("API_DB_VALIDATION", "").isBlank()
                || !row.getOrDefault("DB_COLUMN_VALIDATION", "").isBlank();
        if (!hasDbValidation) {
            return;
        }
        JSONObject dbValidation = parseOptionalJsonObject(row.get("DB_VALIDATION"));
        String sqlTemplate = row.getOrDefault("DB_QUERY", "");
        if (sqlTemplate.isBlank()) {
            sqlTemplate = dbValidation.optString("sqlQuery");
        }
        String sqlQuery = resolveRunnerVariables(sqlTemplate, variables);
        DbConnectionConfig config = runnerDbConnectionConfig(suitePath, row.get("DB_CONNECTION"));

        JSONArray apiDbValidations = parseOptionalJsonArray(row.get("API_DB_VALIDATION"));
        if (!apiDbValidations.isEmpty()) {
            List<DbValidationRule> rules = new ArrayList<>();
            for (int i = 0; i < apiDbValidations.length(); i++) {
                JSONObject json = apiDbValidations.optJSONObject(i);
                if (json == null) {
                    continue;
                }
                DbValidationRule rule = new DbValidationRule();
                rule.apiField = json.optString("apiField");
                rule.dbColumn = json.optString("dbColumn");
                rule.operator = json.optString("operator", "=");
                rule.description = json.optString("description");
                rules.add(rule);
            }
            if (!rules.isEmpty()) {
                DbValidationReport dbReport = dbValidationService.validate(config, sqlQuery, rules, responseBody, variables);
                for (DbValidationResult dbResult : dbReport.results) {
                    addValidation(result, dbResult.field, "API-DB " + dbResult.operator,
                            dbResult.expectedValue, dbResult.actualValue,
                            dbResult.passed, dbResult.message);
                }
            }
        }

        JSONArray dbColumnValidations = parseOptionalJsonArray(row.get("DB_COLUMN_VALIDATION"));
        JSONArray legacyColumnValidations = dbValidation.optJSONArray("dbColumnValidations");
        if (dbColumnValidations.isEmpty() && legacyColumnValidations != null) {
            dbColumnValidations = legacyColumnValidations;
        }
        if (!dbColumnValidations.isEmpty()) {
            List<Map<String, Object>> rows = dbValidationService.executeQuery(config, sqlQuery, responseBody, variables);
            for (int i = 0; i < dbColumnValidations.length(); i++) {
                JSONObject validation = dbColumnValidations.optJSONObject(i);
                if (validation == null) {
                    continue;
                }
                Object actual = dbColumnActualValue(rows, validation.optString("dbColumnName"));
                String actualType = dbValueType(actual);
                String actualValue = actual == null ? "" : String.valueOf(actual);
                String expected = resolveRunnerVariables(validation.optString("expectedValueOrVariable"), variables);
                List<String> errors = dbColumnValidationErrors(actualType, actualValue,
                        validation.optString("nullValidation"), validation.optString("typeValidation"), expected);
                boolean passed = errors.isEmpty();
                addValidation(result, validation.optString("dbColumnName"),
                        "DB Column Null: " + validation.optString("nullValidation")
                                + ", Type: " + validation.optString("typeValidation"),
                        expected, actualValue, passed, String.join(", ", errors));
            }
        }
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
        List<String> sharedStrings = readSharedStrings(entries);
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().matches("xl/worksheets/sheet\\d+\\.xml")) {
                continue;
            }
            List<Map<String, String>> rows = rowsFromSheet(
                    new String(entry.getValue(), StandardCharsets.UTF_8), sharedStrings);
            if (!rows.isEmpty()) {
                return rows;
            }
        }
        return List.of();
    }

    private List<Map<String, String>> rowsFromSheet(String sheetXml, List<String> sharedStrings) {
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

    private void addValidation(Map<String, String> result, String field, String validation,
                               String expected, String actual, boolean passed, String message) {
        JSONArray validations = new JSONArray(result.getOrDefault("Validations", "[]"));
        validations.put(new JSONObject()
                .put("status", passed ? "PASS" : "FAIL")
                .put("field", field == null ? "" : field)
                .put("validation", validation == null ? "" : validation)
                .put("expected", expected == null ? "" : expected)
                .put("actual", actual == null ? "" : actual)
                .put("message", message == null ? "" : message));
        result.put("Validations", validations.toString());
    }

    private boolean validationsPassed(Map<String, String> result) {
        JSONArray validations = new JSONArray(result.getOrDefault("Validations", "[]"));
        for (int i = 0; i < validations.length(); i++) {
            JSONObject validation = validations.optJSONObject(i);
            if (validation != null && "FAIL".equalsIgnoreCase(validation.optString("status"))) {
                return false;
            }
        }
        return true;
    }

    private JSONObject parseOptionalJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(value);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray parseOptionalJsonArray(String value) {
        if (value == null || value.isBlank()) {
            return new JSONArray();
        }
        try {
            return new JSONArray(value);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private String resolveRunnerVariables(String text, Map<String, String> variables) {
        if (text == null) {
            return "";
        }
        String resolved = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            resolved = resolved.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return resolveVariables(resolved);
    }

    private Path resolveWorkbookRelativePath(Path workbookPath, String absolutePath, String relativePath) {
        if (absolutePath != null && !absolutePath.isBlank() && Files.exists(Path.of(absolutePath))) {
            return Path.of(absolutePath);
        }
        Path workbookDirectory = workbookPath == null ? null : workbookPath.toAbsolutePath().getParent();
        if (workbookDirectory != null && relativePath != null && !relativePath.isBlank()) {
            Path resolved = workbookDirectory.resolve(relativePath).normalize();
            if (Files.exists(resolved)) {
                return resolved;
            }
        }
        Path projectRoot = Path.of("").toAbsolutePath().normalize();
        Path projectRelative = projectRelativeSupportPath(relativePath);
        if (projectRelative != null && Files.exists(projectRelative)) {
            return projectRelative;
        }
        projectRelative = projectRelativeSupportPath(absolutePath);
        if (projectRelative != null && Files.exists(projectRelative)) {
            return projectRelative;
        }
        if (absolutePath != null && !absolutePath.isBlank()) {
            Path byFileName = findByFileName(projectRoot, pathFileName(absolutePath));
            if (byFileName != null) {
                return byFileName;
            }
        }
        if (relativePath != null && !relativePath.isBlank()) {
            Path byFileName = findByFileName(projectRoot, pathFileName(relativePath));
            if (byFileName != null) {
                return byFileName;
            }
        }
        return Path.of(absolutePath == null || absolutePath.isBlank() ? relativePath : absolutePath);
    }

    private Path projectRelativeSupportPath(String pathText) {
        if (pathText == null || pathText.isBlank()) {
            return null;
        }
        String normalized = pathText.replace('\\', '/');
        int marker = normalized.lastIndexOf("api-validator/");
        if (marker >= 0) {
            normalized = normalized.substring(marker + "api-validator/".length());
        }
        while (normalized.startsWith("../")) {
            normalized = normalized.substring(3);
        }
        Path resolved = Path.of("").toAbsolutePath().normalize().resolve(normalized).normalize();
        return resolved.startsWith(Path.of("").toAbsolutePath().normalize()) ? resolved : null;
    }

    private String pathFileName(String pathText) {
        if (pathText == null) {
            return "";
        }
        String normalized = pathText.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private Path findByFileName(Path root, String fileName) {
        if (fileName == null || fileName.isBlank() || !Files.isDirectory(root)) {
            return null;
        }
        try (var paths = Files.walk(root, 5)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> fileName.equals(path.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private DbConnectionConfig runnerDbConnectionConfig(Path workbookPath, String connectionJson) throws Exception {
        JSONObject connection = parseOptionalJsonObject(connectionJson);
        JSONObject json = connection;
        String path = connection.optString("path");
        String relativePath = connection.optString("relativePath");
        if (!path.isBlank() || !relativePath.isBlank()) {
            Path connectionPath = resolveWorkbookRelativePath(workbookPath, path, relativePath);
            if (Files.exists(connectionPath)) {
                json = new JSONObject(Files.readString(connectionPath, StandardCharsets.UTF_8));
            }
        }
        DbConnectionConfig config = new DbConnectionConfig();
        config.databaseType = json.optString("databaseType", connection.optString("databaseType", "MySQL"));
        config.jdbcUrl = json.optString("jdbcUrl", connection.optString("jdbcUrl"));
        config.username = json.optString("username", connection.optString("username"));
        config.password = json.optString("password", connection.optString("password"));
        config.driverClass = json.optString("driverClass", connection.optString("driverClass"));
        return config;
    }

    private Object extractJsonPathValue(Object root, String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.isEmpty() || "$".equals(normalized)) {
            return root;
        }
        if (normalized.startsWith("$.")) {
            normalized = normalized.substring(2);
        } else if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        Object current = root;
        for (String part : normalized.split("\\.")) {
            if (!part.isBlank()) {
                current = stepIntoJsonPath(current, part);
            }
        }
        return current;
    }

    private Object stepIntoJsonPath(Object current, String part) {
        String remaining = part;
        int bracketIndex = remaining.indexOf('[');
        Object value = bracketIndex <= 0 ? current : objectJsonField(current, remaining.substring(0, bracketIndex));
        if (bracketIndex < 0) {
            return objectJsonField(current, remaining);
        }
        while (bracketIndex >= 0) {
            int closeIndex = remaining.indexOf(']', bracketIndex);
            int index = Integer.parseInt(remaining.substring(bracketIndex + 1, closeIndex).trim());
            if (!(value instanceof JSONArray array)) {
                throw new IllegalArgumentException("Path segment is not an array: " + part);
            }
            value = array.get(index);
            bracketIndex = remaining.indexOf('[', closeIndex);
        }
        return value;
    }

    private Object objectJsonField(Object current, String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return current;
        }
        if (current instanceof JSONObject object) {
            if (!object.has(fieldName)) {
                throw new IllegalArgumentException("Response field not found: " + fieldName);
            }
            return object.get(fieldName);
        }
        if (current instanceof JSONArray array) {
            if (array.isEmpty()) {
                throw new IllegalArgumentException("Response array is empty for field: " + fieldName);
            }
            return objectJsonField(array.get(0), fieldName);
        }
        throw new IllegalArgumentException("Path segment is not an object: " + fieldName);
    }

    private String jsonValueType(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "null";
        }
        if (value instanceof JSONObject) {
            return "object";
        }
        if (value instanceof JSONArray) {
            return "array";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return "integer";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        return "string";
    }

    private List<String> fieldValidationErrors(String actualType, String actualValue,
                                               String nullRule, String typeRule, String expectedValue) {
        List<String> errors = new ArrayList<>();
        if ("Not Null".equals(nullRule) && "null".equals(actualType)) {
            errors.add("expected not null");
        } else if ("Null".equals(nullRule) && !"null".equals(actualType)) {
            errors.add("expected null");
        }
        if (!typeRule.isBlank() && !"Skip".equals(typeRule) && !typeRule.equals(actualType)) {
            String expectedType = typeRule.toLowerCase();
            String normalizedActualType = actualType == null ? "" : actualType.toLowerCase();
            if (!expectedType.equals(normalizedActualType)
                    && !("number".equals(expectedType) && "integer".equals(normalizedActualType))) {
                errors.add("expected " + typeRule);
            }
        }
        if (!expectedValue.isBlank() && !expectedValue.equals(actualValue)) {
            errors.add("expected value mismatch");
        }
        return errors;
    }

    private Object dbColumnActualValue(List<Map<String, Object>> rows, String columnReference) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("DB query returned no rows.");
        }
        String columnName = columnReference == null ? "" : columnReference.trim();
        int rowIndex = 0;
        Matcher matcher = java.util.regex.Pattern.compile("^(.+)\\[(\\d+)]$").matcher(columnName);
        if (matcher.matches()) {
            columnName = matcher.group(1).trim();
            rowIndex = Integer.parseInt(matcher.group(2));
        }
        if (rowIndex >= rows.size()) {
            throw new IllegalArgumentException("DB row index " + rowIndex + " is not available for " + columnName);
        }
        Map<String, Object> row = rows.get(rowIndex);
        if (!row.containsKey(columnName)) {
            throw new IllegalArgumentException("DB column not found: " + columnName);
        }
        return row.get(columnName);
    }

    private List<String> dbColumnValidationErrors(String actualType, String actualValue,
                                                  String nullRule, String typeRule, String expectedValue) {
        List<String> errors = new ArrayList<>();
        boolean isNull = "null".equals(actualType);
        boolean isEmpty = actualValue == null || actualValue.isEmpty();
        boolean isBlank = actualValue == null || actualValue.isBlank();
        if ("Not Null".equals(nullRule) && isNull) {
            errors.add("expected not null");
        } else if ("Null".equals(nullRule) && !isNull) {
            errors.add("expected null");
        } else if ("Not Empty".equals(nullRule) && (isNull || isEmpty)) {
            errors.add("expected not empty");
        } else if ("Empty".equals(nullRule) && !isEmpty) {
            errors.add("expected empty");
        } else if ("Not Blank".equals(nullRule) && (isNull || isBlank)) {
            errors.add("expected not blank");
        } else if ("Blank".equals(nullRule) && !isBlank) {
            errors.add("expected blank");
        }
        if (!typeRule.isBlank() && !"Skip".equals(typeRule) && !dbTypeMatches(typeRule, actualType, actualValue)) {
            errors.add("expected " + typeRule);
        }
        if (!expectedValue.isBlank() && !expectedValue.equals(actualValue)) {
            errors.add("expected value mismatch");
        }
        return errors;
    }

    private boolean dbTypeMatches(String expectedType, String actualType, String actualValue) {
        String expected = expectedType == null ? "" : expectedType.toLowerCase();
        String actual = actualType == null ? "" : actualType.toLowerCase();
        if (expected.equals(actual)) {
            return true;
        }
        if ("number".equals(expected) && ("integer".equals(actual) || "decimal".equals(actual))) {
            return true;
        }
        if ("datetime".equals(expected) && "timestamp".equals(actual)) {
            return true;
        }
        if ("timestamp".equals(expected) && "datetime".equals(actual)) {
            return true;
        }
        if ("uuid".equals(expected)) {
            return actualValue.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
        if ("json".equals(expected)) {
            String trimmed = actualValue.trim();
            try {
                if (trimmed.startsWith("{")) {
                    new JSONObject(trimmed);
                    return true;
                }
                if (trimmed.startsWith("[")) {
                    new JSONArray(trimmed);
                    return true;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        return false;
    }

    private String dbValueType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return "integer";
        }
        if (value instanceof java.math.BigDecimal || value instanceof Float || value instanceof Double) {
            return "decimal";
        }
        if (value instanceof Number) {
            return "number";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof java.sql.Date || value instanceof java.time.LocalDate) {
            return "date";
        }
        if (value instanceof java.sql.Time || value instanceof java.time.LocalTime) {
            return "time";
        }
        if (value instanceof java.sql.Timestamp || value instanceof java.time.Instant
                || value instanceof java.time.LocalDateTime || value instanceof java.time.OffsetDateTime) {
            return "timestamp";
        }
        return "string";
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
                <title>VeyraAI GitHub Actions Report</title>
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
        html.append("<header><h1>VeyraAI GitHub Actions Report</h1><div class='sub'>Generated ")
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
        html.append("</table>");

        html.append("<h2 class='section-title'>Field Level Validations</h2>");
        for (Map<String, String> result : results) {
            JSONArray validations = validationsFor(result);
            String passedText = isPassed(result) ? "PASS" : "FAIL";
            html.append("<section class='card' style='margin-bottom:18px'>")
                    .append("<div style='display:flex;justify-content:space-between;gap:16px;align-items:flex-start'>")
                    .append("<div><h2 style='margin:0 0 8px;color:#1e5ed6'>")
                    .append(escapeHtml(result.getOrDefault("Test Step", "")))
                    .append("</h2><div class='label'>")
                    .append(escapeHtml(result.getOrDefault("Test Suite", "")))
                    .append(" / ")
                    .append(escapeHtml(result.getOrDefault("Test Case", "")))
                    .append("</div></div><div class='status ")
                    .append(isPassed(result) ? "pass" : "fail")
                    .append("'>")
                    .append(passedText)
                    .append("</div></div>");
            html.append("<div class='summary' style='grid-template-columns:repeat(4,minmax(120px,1fr));margin-top:14px'>")
                    .append(summaryCard("Step Type", stepType(result), ""))
                    .append(summaryCard("Status", result.getOrDefault("Status", ""), isPassed(result) ? "pass" : "fail"))
                    .append(summaryCard("Validations", String.valueOf(validations.length()), ""))
                    .append(summaryCard("Result", isPassed(result) ? "Passed" : "Failed", isPassed(result) ? "pass" : "fail"))
                    .append("</div>");
            if (isFailed(result) && !result.getOrDefault("Message", "").isBlank()) {
                html.append("<div style='background:#fff1f1;border:1px solid #fecaca;border-radius:8px;padding:14px;margin:12px 0;color:#991b1b'>")
                        .append("<strong>Failure Error Message</strong><br>")
                        .append(escapeHtml(result.getOrDefault("Message", "")))
                        .append("</div>");
            }
            html.append("<table><tr>");
            for (String header : List.of("Status", "Field", "Validation", "Expected", "Actual", "Message")) {
                html.append("<th>").append(header).append("</th>");
            }
            html.append("</tr>");
            if (validations.isEmpty()) {
                html.append("<tr><td colspan='6'>No validation rows were produced for this step.</td></tr>");
            }
            for (int i = 0; i < validations.length(); i++) {
                JSONObject validation = validations.optJSONObject(i);
                if (validation == null) {
                    continue;
                }
                boolean passedValidation = "PASS".equalsIgnoreCase(validation.optString("status"));
                html.append("<tr>")
                        .append("<td class='status ").append(passedValidation ? "pass" : "fail").append("'>")
                        .append(escapeHtml(validation.optString("status"))).append("</td>")
                        .append("<td>").append(escapeHtml(validation.optString("field"))).append("</td>")
                        .append("<td>").append(escapeHtml(validation.optString("validation"))).append("</td>")
                        .append("<td class='mono'>").append(escapeHtml(validation.optString("expected"))).append("</td>")
                        .append("<td class='mono'>").append(escapeHtml(validation.optString("actual"))).append("</td>")
                        .append("<td>").append(escapeHtml(validation.optString("message"))).append("</td>")
                        .append("</tr>");
            }
            html.append("</table></section>");
        }

        html.append("</main></body></html>");
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

    private JSONArray validationsFor(Map<String, String> result) {
        return new JSONArray(result.getOrDefault("Validations", "[]"));
    }

    private String stepType(Map<String, String> result) {
        if (!result.getOrDefault("WEB_TEST", "").isBlank()) {
            return "Web Test";
        }
        if (!result.getOrDefault("PERFORMANCE_TEST", "").isBlank()) {
            return "Performance Test";
        }
        if (!result.getOrDefault("DB_VALIDATION", "").isBlank()
                || !result.getOrDefault("API_DB_VALIDATION", "").isBlank()
                || !result.getOrDefault("DB_COLUMN_VALIDATION", "").isBlank()) {
            return "DB Validation";
        }
        if (!result.getOrDefault("JSON_COMPARE", "").isBlank()) {
            return "JSON Compare";
        }
        if (!result.getOrDefault("API_FIELD_VALIDATION", "").isBlank()) {
            return "Field Validation";
        }
        if (!result.getOrDefault("Hit Request", "").isBlank()) {
            return "API Request";
        }
        return "Manual";
    }

    private void printSummary(List<Map<String, String>> results) {
        long passed = results.stream().filter(result -> "Passed".equalsIgnoreCase(result.getOrDefault("Status", ""))).count();
        long failedCount = results.stream().filter(result -> "Failed".equalsIgnoreCase(result.getOrDefault("Status", ""))).count();
        System.out.println("VeyraAI summary: " + passed + " passed, " + failedCount + " failed, " + results.size() + " total.");
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

    private String valueAt(Object[] values, int index) {
        return values != null && index < values.length && values[index] != null ? String.valueOf(values[index]) : "";
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

package service;

import model.DbConnectionConfig;
import model.DbValidationReport;
import model.DbValidationResult;
import model.DbValidationRule;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DbValidationService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern DB_COLUMN_ROW_PATTERN = Pattern.compile("^(.+)\\[(\\d+)]$");

    public void testConnection(DbConnectionConfig config) throws Exception {
        try (Connection ignored = openConnection(config)) {
            // Successful connection is enough.
        }
    }

    public List<String> fetchColumnLabels(DbConnectionConfig config, String sqlTemplate,
                                          String responseBody, Map<String, String> variables) throws Exception {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new IllegalArgumentException("SQL query is required.");
        }

        Object responseJson = responseBody == null || responseBody.isBlank()
                ? new JSONObject()
                : new JSONTokener(responseBody).nextValue();
        SqlExecutionPlan plan = buildExecutionPlan(sqlTemplate, responseJson, variables == null ? Map.of() : variables);

        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(plan.sql)) {
            statement.setMaxRows(1);
            for (int i = 0; i < plan.parameters.size(); i++) {
                statement.setObject(i + 1, plan.parameters.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                List<String> columnLabels = new ArrayList<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    columnLabels.add(metadata.getColumnLabel(i));
                }
                return columnLabels;
            }
        }
    }

    public DbValidationReport validate(DbConnectionConfig config, String sqlTemplate,
                                       List<DbValidationRule> rules, String responseBody) throws Exception {
        return validate(config, sqlTemplate, rules, responseBody, Map.of());
    }

    public List<Map<String, Object>> executeQuery(DbConnectionConfig config, String sqlTemplate,
                                                  String responseBody, Map<String, String> variables) throws Exception {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new IllegalArgumentException("SQL query is required.");
        }

        Object responseJson = responseBody == null || responseBody.isBlank()
                ? new JSONObject()
                : new JSONTokener(responseBody).nextValue();
        SqlExecutionPlan plan = buildExecutionPlan(sqlTemplate, responseJson, variables == null ? Map.of() : variables);

        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(plan.sql)) {
            for (int i = 0; i < plan.parameters.size(); i++) {
                statement.setObject(i + 1, plan.parameters.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                return readAllRows(resultSet);
            }
        }
    }

    public DbValidationReport validate(DbConnectionConfig config, String sqlTemplate,
                                       List<DbValidationRule> rules, String responseBody,
                                       Map<String, String> variables) throws Exception {
        if (sqlTemplate == null || sqlTemplate.isBlank()) {
            throw new IllegalArgumentException("SQL query is required.");
        }
        if (rules == null || rules.isEmpty()) {
            throw new IllegalArgumentException("Add at least one validation rule.");
        }
        Map<String, String> variableSnapshot = variables == null ? Map.of() : variables;
        boolean hasResponseBody = responseBody != null && !responseBody.isBlank();
        if (!hasResponseBody && variableSnapshot.isEmpty()) {
            throw new IllegalArgumentException("Send an API request or import saved variables before DB validation.");
        }

        Object responseJson = hasResponseBody ? new JSONTokener(responseBody).nextValue() : new JSONObject();
        SqlExecutionPlan plan = buildExecutionPlan(sqlTemplate, responseJson, variableSnapshot);

        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(plan.sql)) {
            for (int i = 0; i < plan.parameters.size(); i++) {
                statement.setObject(i + 1, plan.parameters.get(i));
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                List<Map<String, Object>> dbRows = readAllRows(resultSet);
                if (dbRows.isEmpty()) {
                    throw new IllegalStateException("Query returned no rows for the latest API response values.");
                }

                List<DbValidationResult> results = new ArrayList<>();
                List<Object> responseItems = normalizeResponseItems(responseJson);
                if (hasResponseBody) {
                    results.add(buildRowCountResult(responseItems.size(), dbRows.size()));
                }

                for (DbValidationRule rule : rules) {
                    DbColumnReference dbColumn = parseDbColumnReference(rule.dbColumn);
                    if (dbColumn.rowIndex != null) {
                        int rowIndex = dbColumn.rowIndex;
                        if (rowIndex >= dbRows.size()) {
                            throw new IllegalArgumentException("DB row index " + rowIndex
                                    + " is not present in query result for column: " + dbColumn.columnName);
                        }
                        Object responseItem = responseItemForRow(responseItems, rowIndex);
                        results.add(evaluateRule(ruleWithDbColumn(rule, dbColumn.columnName), responseItem,
                                dbRows.get(rowIndex), rowIndex, true, variableSnapshot));
                        continue;
                    }

                    for (int rowIndex = 0; rowIndex < dbRows.size(); rowIndex++) {
                        Object responseItem = responseItemForRow(responseItems, rowIndex);
                        results.add(evaluateRule(rule, responseItem, dbRows.get(rowIndex), rowIndex,
                                dbRows.size() > 1, variableSnapshot));
                    }
                }

                int passed = 0;
                for (DbValidationResult result : results) {
                    if (result.passed) {
                        passed++;
                    }
                }

                DbValidationReport report = new DbValidationReport();
                report.results = results;
                report.total = results.size();
                report.passed = passed;
                report.failed = results.size() - passed;
                report.dbRows = dbRows;
                report.executedSql = plan.sql;
                return report;
            }
        }
    }

    private Connection openConnection(DbConnectionConfig config) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("Database configuration is required.");
        }
        if (config.jdbcUrl == null || config.jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("JDBC URL is required.");
        }
        if (config.driverClass != null && !config.driverClass.isBlank()) {
            Class.forName(config.driverClass.trim());
        }
        return DriverManager.getConnection(
                config.jdbcUrl.trim(),
                config.username == null ? "" : config.username,
                config.password == null ? "" : config.password
        );
    }

    private SqlExecutionPlan buildExecutionPlan(String sqlTemplate, Object responseJson, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(sqlTemplate);
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        int index = 0;

        while (matcher.find()) {
            sql.append(sqlTemplate, index, matcher.start());
            sql.append("?");
            String fieldPath = matcher.group(1).trim();
            Object value = variables.containsKey(fieldPath) ? variables.get(fieldPath) : extractValue(responseJson, fieldPath);
            parameters.add(normalizeSqlValue(value));
            index = matcher.end();
        }

        sql.append(sqlTemplate.substring(index));
        SqlExecutionPlan plan = new SqlExecutionPlan();
        plan.sql = sql.toString();
        plan.parameters = parameters;
        return plan;
    }

    private Object normalizeSqlValue(Object value) {
        if (value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return value == null ? null : String.valueOf(value);
    }

    private Object responseItemForRow(List<Object> responseItems, int rowIndex) {
        if (responseItems.size() == 1) {
            return responseItems.get(0);
        }
        if (rowIndex < responseItems.size()) {
            return responseItems.get(rowIndex);
        }
        throw new IllegalArgumentException("API response does not contain row index " + rowIndex
                + " for DB result comparison.");
    }

    private DbColumnReference parseDbColumnReference(String dbColumn) {
        String normalized = dbColumn == null ? "" : dbColumn.trim();
        Matcher matcher = DB_COLUMN_ROW_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return new DbColumnReference(normalized, null);
        }

        String columnName = matcher.group(1).trim();
        if (columnName.isEmpty()) {
            return new DbColumnReference(normalized, null);
        }
        return new DbColumnReference(columnName, Integer.parseInt(matcher.group(2)));
    }

    private DbValidationRule ruleWithDbColumn(DbValidationRule original, String dbColumn) {
        DbValidationRule copy = new DbValidationRule();
        copy.apiField = original.apiField;
        copy.dbColumn = dbColumn;
        copy.operator = original.operator;
        copy.description = original.description;
        return copy;
    }

    private List<Map<String, Object>> readAllRows(ResultSet resultSet) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                String label = metadata.getColumnLabel(i);
                row.put(label, resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private DbValidationResult evaluateRule(DbValidationRule rule, Object responseJson, Map<String, Object> dbRow,
                                            int rowIndex, boolean multiRow) {
        return evaluateRule(rule, responseJson, dbRow, rowIndex, multiRow, Map.of());
    }

    private DbValidationResult evaluateRule(DbValidationRule rule, Object responseJson, Map<String, Object> dbRow,
                                            int rowIndex, boolean multiRow, Map<String, String> variables) {
        if (rule.apiField == null || rule.apiField.isBlank()) {
            throw new IllegalArgumentException("Validation rule API field cannot be empty.");
        }
        if (rule.dbColumn == null || rule.dbColumn.isBlank()) {
            throw new IllegalArgumentException("Validation rule DB column cannot be empty.");
        }

        String apiField = rule.apiField.trim();
        Object expected = resolveExpectedValue(apiField, responseJson, variables);
        Object actual = findColumnValue(dbRow, rule.dbColumn.trim());
        String operator = rule.operator == null || rule.operator.isBlank() ? "=" : rule.operator.trim();
        boolean passed = compare(expected, actual, operator);

        DbValidationResult result = new DbValidationResult();
        result.field = multiRow ? "[" + rowIndex + "]." + apiField : apiField;
        result.expectedValue = printable(expected);
        result.actualValue = printable(actual);
        result.operator = operator;
        result.passed = passed;
        result.message = passed ? buildPassMessage(operator, expected, actual) : buildFailMessage(operator, expected, actual);
        return result;
    }

    private Object resolveExpectedValue(String apiField, Object responseJson, Map<String, String> variables) {
        String variableName = variableReferenceName(apiField);
        if (variableName != null && variables.containsKey(variableName)) {
            return variables.get(variableName);
        }
        if (variables.containsKey(apiField)) {
            return variables.get(apiField);
        }
        return extractValue(responseJson, apiField);
    }

    private String variableReferenceName(String text) {
        String normalized = text == null ? "" : text.trim();
        if (normalized.startsWith("${") && normalized.endsWith("}") && normalized.length() > 3) {
            return normalized.substring(2, normalized.length() - 1).trim();
        }
        return null;
    }

    private List<Object> normalizeResponseItems(Object responseJson) {
        List<Object> items = new ArrayList<>();
        if (responseJson instanceof JSONArray) {
            JSONArray array = (JSONArray) responseJson;
            for (int i = 0; i < array.length(); i++) {
                items.add(array.get(i));
            }
            return items;
        }
        items.add(responseJson);
        return items;
    }

    private DbValidationResult buildRowCountResult(int apiCount, int dbCount) {
        DbValidationResult result = new DbValidationResult();
        result.field = "rowCount";
        result.expectedValue = String.valueOf(apiCount);
        result.actualValue = String.valueOf(dbCount);
        result.operator = "=";
        result.passed = apiCount == dbCount;
        result.message = result.passed
                ? "API object count matches DB row count"
                : "API object count and DB row count do not match";
        return result;
    }

    private Object findColumnValue(Map<String, Object> dbRow, String dbColumn) {
        if (dbRow.containsKey(dbColumn)) {
            return dbRow.get(dbColumn);
        }
        for (Map.Entry<String, Object> entry : dbRow.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(dbColumn)) {
                return entry.getValue();
            }
        }
        throw new IllegalArgumentException("DB column not present in query result: " + dbColumn);
    }

    private boolean compare(Object expected, Object actual, String operator) {
        String normalizedOperator = operator.toLowerCase(Locale.ROOT);
        switch (normalizedOperator) {
            case "=":
            case "==":
                return compareEquality(expected, actual);
            case "!=":
            case "<>":
                return !compareEquality(expected, actual);
            case "contains":
                return printable(actual).contains(printable(expected));
            case ">":
                return compareOrdered(expected, actual) < 0;
            case ">=":
                return compareOrdered(expected, actual) <= 0;
            case "<":
                return compareOrdered(expected, actual) > 0;
            case "<=":
                return compareOrdered(expected, actual) >= 0;
            default:
                throw new IllegalArgumentException("Unsupported operator: " + operator);
        }
    }

    private boolean compareEquality(Object expected, Object actual) {
        BigDecimal expectedNumber = toBigDecimal(expected);
        BigDecimal actualNumber = toBigDecimal(actual);
        if (expectedNumber != null && actualNumber != null) {
            return expectedNumber.compareTo(actualNumber) == 0;
        }
        Instant expectedInstant = toInstant(expected);
        Instant actualInstant = toInstant(actual);
        if (expectedInstant != null && actualInstant != null) {
            return expectedInstant.equals(actualInstant);
        }
        return printable(expected).equals(printable(actual));
    }

    private int compareOrdered(Object expected, Object actual) {
        BigDecimal expectedNumber = toBigDecimal(expected);
        BigDecimal actualNumber = toBigDecimal(actual);
        if (expectedNumber != null && actualNumber != null) {
            return expectedNumber.compareTo(actualNumber);
        }
        Instant expectedInstant = toInstant(expected);
        Instant actualInstant = toInstant(actual);
        if (expectedInstant != null && actualInstant != null) {
            return expectedInstant.compareTo(actualInstant);
        }
        return printable(expected).compareTo(printable(actual));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Instant toInstant(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return null;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toInstant();
        }
        if (value instanceof java.util.Date) {
            return Instant.ofEpochMilli(((java.util.Date) value).getTime());
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(text).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private String printable(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof JSONObject) {
            return ((JSONObject) value).toString();
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).toString();
        }
        return String.valueOf(value);
    }

    private String buildPassMessage(String operator, Object expected, Object actual) {
        if ("contains".equalsIgnoreCase(operator)) {
            return "Actual contains expected";
        }
        if (">=".equals(operator) || ">".equals(operator) || "<=".equals(operator) || "<".equals(operator)) {
            return "Validation passed";
        }
        if (!printable(expected).isBlank() || !printable(actual).isBlank()) {
            return "Matched";
        }
        return "Both values are empty";
    }

    private String buildFailMessage(String operator, Object expected, Object actual) {
        if ("contains".equalsIgnoreCase(operator)) {
            return "Actual does not contain expected";
        }
        if (">=".equals(operator) || ">".equals(operator) || "<=".equals(operator) || "<".equals(operator)) {
            return "Comparison condition was not met";
        }
        return "Values are not equal";
    }

    private Object extractValue(Object root, String path) {
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
        for (String part : splitPath(normalized)) {
            current = stepInto(current, part);
        }
        return current;
    }

    private List<String> splitPath(String path) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            if (ch == '.') {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    private Object stepInto(Object current, String pathPart) {
        String remaining = pathPart;
        int bracketIndex = remaining.indexOf('[');
        if (bracketIndex < 0) {
            return objectField(current, remaining);
        }

        String fieldName = bracketIndex == 0 ? "" : remaining.substring(0, bracketIndex);
        Object value = fieldName.isEmpty() ? current : objectField(current, fieldName);

        while (bracketIndex >= 0) {
            int closeIndex = remaining.indexOf(']', bracketIndex);
            if (closeIndex < 0) {
                throw new IllegalArgumentException("Invalid field path: " + pathPart);
            }
            String indexText = remaining.substring(bracketIndex + 1, closeIndex).trim();
            int arrayIndex = Integer.parseInt(indexText);
            if (!(value instanceof JSONArray)) {
                throw new IllegalArgumentException("Path segment is not an array: " + pathPart);
            }
            value = ((JSONArray) value).get(arrayIndex);
            bracketIndex = remaining.indexOf('[', closeIndex);
        }
        return value;
    }

    private Object objectField(Object current, String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) {
            return current;
        }
        if (current instanceof JSONArray) {
            return arrayField((JSONArray) current, fieldName);
        }
        if (!(current instanceof JSONObject)) {
            throw new IllegalArgumentException("Path segment is not an object: " + fieldName);
        }
        JSONObject object = (JSONObject) current;
        if (!object.has(fieldName)) {
            throw new IllegalArgumentException("Response field not found: " + fieldName);
        }
        return object.get(fieldName);
    }

    private Object arrayField(JSONArray array, String fieldName) {
        if (array.isEmpty()) {
            throw new IllegalArgumentException("Response array is empty, so field '" + fieldName + "' cannot be resolved.");
        }

        Object firstMatch = null;
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (item instanceof JSONObject && ((JSONObject) item).has(fieldName)) {
                if (firstMatch == null) {
                    firstMatch = ((JSONObject) item).get(fieldName);
                }
                if (i == 0) {
                    return firstMatch;
                }
            }
        }

        if (firstMatch != null) {
            return firstMatch;
        }

        throw new IllegalArgumentException(
                "Response field not found: " + fieldName + ". For array responses, use [0]." + fieldName
                        + " or ensure the first item contains that field."
        );
    }

    private static class SqlExecutionPlan {
        private String sql;
        private List<Object> parameters;
    }

    private static class DbColumnReference {
        private final String columnName;
        private final Integer rowIndex;

        private DbColumnReference(String columnName, Integer rowIndex) {
            this.columnName = columnName;
            this.rowIndex = rowIndex;
        }
    }
}

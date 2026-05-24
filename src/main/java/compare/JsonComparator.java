package compare;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.skyscreamer.jsonassert.JSONCompare;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.skyscreamer.jsonassert.JSONCompareResult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JsonComparator {

    public List<Object[]> compare(String expected, String actual, boolean strict) {
        return compare(expected, actual, strict, false);
    }

    public List<Object[]> compare(String expected, String actual, boolean strict, boolean includeMatches) {
        List<Object[]> results = new ArrayList<>();

        try {
            Object expectedJson = parseJson(expected);
            Object actualJson = parseJson(actual);

            compareValues("$", expectedJson, actualJson, strict, includeMatches, results);

            if (results.isEmpty()) {
                JSONCompareMode mode = strict ? JSONCompareMode.STRICT : JSONCompareMode.LENIENT;
                JSONCompareResult result = JSONCompare.compareJSON(expected, actual, mode);
                if (result.passed()) {
                    results.add(new Object[]{"Match", "$", stringify(expectedJson), stringify(actualJson)});
                }
            }
        } catch (Exception e) {
            results.clear();
            results.add(new Object[]{"Error", "$", safeTrim(expected), safeTrim(actual)});
            results.add(new Object[]{"Message", "$", e.getMessage(), ""});
        }

        return results;
    }

    private Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Expected and actual JSON content must not be empty.");
        }
        return new JSONTokener(json).nextValue();
    }

    private void compareValues(String path, Object expected, Object actual, boolean strict, boolean includeMatches, List<Object[]> results) {
        if (isJsonNull(expected) && isJsonNull(actual)) {
            if (includeMatches) {
                results.add(new Object[]{"Match", path, "", ""});
            }
            return;
        }
        if (isJsonNull(expected) || isJsonNull(actual)) {
            results.add(new Object[]{"Changed", path, stringify(expected), stringify(actual)});
            return;
        }

        if (expected instanceof JSONObject && actual instanceof JSONObject) {
            compareObjects(path, (JSONObject) expected, (JSONObject) actual, strict, includeMatches, results);
            return;
        }

        if (expected instanceof JSONArray && actual instanceof JSONArray) {
            compareArrays(path, (JSONArray) expected, (JSONArray) actual, strict, includeMatches, results);
            return;
        }

        if (valuesEqual(expected, actual)) {
            if (includeMatches) {
                results.add(new Object[]{"Match", path, stringify(expected), stringify(actual)});
            }
        } else {
            results.add(new Object[]{"Changed", path, stringify(expected), stringify(actual)});
        }
    }

    private void compareObjects(String path, JSONObject expected, JSONObject actual, boolean strict, boolean includeMatches, List<Object[]> results) {
        for (String key : expected.keySet()) {
            String childPath = childPath(path, key);
            if (!actual.has(key)) {
                results.add(new Object[]{"Missing", childPath, stringify(expected.get(key)), ""});
                continue;
            }
            compareValues(childPath, expected.get(key), actual.get(key), strict, includeMatches, results);
        }

        if (strict) {
            for (String key : actual.keySet()) {
                if (!expected.has(key)) {
                    String childPath = childPath(path, key);
                    results.add(new Object[]{"New", childPath, "", stringify(actual.get(key))});
                }
            }
        }
    }

    private void compareArrays(String path, JSONArray expected, JSONArray actual, boolean strict, boolean includeMatches, List<Object[]> results) {
        int minLength = Math.min(expected.length(), actual.length());
        for (int i = 0; i < minLength; i++) {
            compareValues(path + "[" + i + "]", expected.get(i), actual.get(i), strict, includeMatches, results);
        }

        if (expected.length() > actual.length()) {
            for (int i = actual.length(); i < expected.length(); i++) {
                results.add(new Object[]{"Missing", path + "[" + i + "]", stringify(expected.get(i)), ""});
            }
        }

        if (strict && actual.length() > expected.length()) {
            for (int i = expected.length(); i < actual.length(); i++) {
                results.add(new Object[]{"New", path + "[" + i + "]", "", stringify(actual.get(i))});
            }
        }
    }

    private boolean valuesEqual(Object expected, Object actual) {
        if (expected instanceof Number && actual instanceof Number) {
            BigDecimal left = new BigDecimal(expected.toString());
            BigDecimal right = new BigDecimal(actual.toString());
            return left.compareTo(right) == 0;
        }
        return Objects.equals(String.valueOf(expected), String.valueOf(actual));
    }

    private boolean isJsonNull(Object value) {
        return value == null || value == JSONObject.NULL;
    }

    private String stringify(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof JSONObject) {
            return ((JSONObject) value).toString(2);
        }
        if (value instanceof JSONArray) {
            return ((JSONArray) value).toString(2);
        }
        return String.valueOf(value);
    }

    private String childPath(String base, String key) {
        return "$".equals(base) ? "$." + key : base + "." + key;
    }

    private String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }
}

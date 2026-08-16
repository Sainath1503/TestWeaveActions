package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/** Validates the model contract before any workbook or persistent memory is written. */
public final class TestPlanningResponseValidator {
    public JSONObject parseAndValidate(String output) {
        String text = output == null ? "" : output.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("Test Planning Agent did not return a JSON object.");
        }
        JSONObject result = new JSONObject(text.substring(start, end + 1));
        JSONArray strategy = requiredArray(result, "testStrategy");
        JSONArray cases = requiredArray(result, "testCases");
        JSONArray data = requiredArray(result, "testData");
        JSONArray traceability = requiredArray(result, "traceability");
        if (strategy.isEmpty()) throw new IllegalArgumentException("Generated test strategy is empty.");
        if (cases.isEmpty()) throw new IllegalArgumentException("Generated test cases are empty.");

        Set<String> caseIds = uniqueIds(cases, "testCaseId", "test case");
        Set<String> dataIds = uniqueIds(data, "testDataId", "test data");
        for (int i = 0; i < cases.length(); i++) {
            JSONObject testCase = cases.getJSONObject(i);
            requireText(testCase, "scenario", "test case " + testCase.optString("testCaseId"));
            requireText(testCase, "expectedResult", "test case " + testCase.optString("testCaseId"));
            requireText(testCase, "baseUri", "test case " + testCase.optString("testCaseId"));
            requireText(testCase, "endpoint", "test case " + testCase.optString("testCaseId"));
            requireText(testCase, "httpMethod", "test case " + testCase.optString("testCaseId"));
            requireText(testCase, "requestPayload", "test case " + testCase.optString("testCaseId"));
            requireText(testCase, "expectedReturnCode", "test case " + testCase.optString("testCaseId"));
            JSONArray linkedData = testCase.optJSONArray("testDataIds");
            if (linkedData != null) {
                for (int j = 0; j < linkedData.length(); j++) {
                    String id = linkedData.optString(j);
                    if (!id.isBlank() && !dataIds.contains(id)) {
                        throw new IllegalArgumentException("Test case references unknown test data ID: " + id);
                    }
                }
            }
        }
        for (int i = 0; i < traceability.length(); i++) {
            JSONArray linkedCases = traceability.getJSONObject(i).optJSONArray("testCaseIds");
            if (linkedCases == null) continue;
            for (int j = 0; j < linkedCases.length(); j++) {
                String id = linkedCases.optString(j);
                if (!id.isBlank() && !caseIds.contains(id)) {
                    throw new IllegalArgumentException("Traceability references unknown test case ID: " + id);
                }
            }
        }
        if (result.optJSONArray("memory") == null) result.put("memory", new JSONArray());
        return result;
    }

    private JSONArray requiredArray(JSONObject object, String name) {
        JSONArray value = object.optJSONArray(name);
        if (value == null) throw new IllegalArgumentException("Test Planning Agent response is missing " + name + ".");
        return value;
    }

    private Set<String> uniqueIds(JSONArray rows, String field, String label) {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String id = requireText(row, field, label + " row " + (i + 1));
            if (!ids.add(id)) throw new IllegalArgumentException("Duplicate " + label + " ID: " + id);
        }
        return ids;
    }

    private String requireText(JSONObject object, String field, String owner) {
        String value = object.optString(field).trim();
        if (value.isBlank()) throw new IllegalArgumentException(owner + " is missing " + field + ".");
        return value;
    }
}

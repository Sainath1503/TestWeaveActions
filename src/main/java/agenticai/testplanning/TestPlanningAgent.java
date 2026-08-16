package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

/** One orchestrating Test Planning Agent using the model connection owned by the host application. */
public final class TestPlanningAgent {
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(String prompt) throws Exception;
    }

    private final TestPlanningPromptBuilder prompts;
    private final TestPlanningResponseValidator validator;

    public TestPlanningAgent(TestPlanningPromptBuilder prompts, TestPlanningResponseValidator validator) {
        this.prompts = prompts;
        this.validator = validator;
    }

    public JSONObject analyze(JSONObject sourceContext, JSONArray relevantMemory,
                              String userInstructions, ModelInvoker model) throws Exception {
        if (sourceContext == null || sourceContext.optJSONArray("sources") == null
                || sourceContext.getJSONArray("sources").isEmpty()) {
            throw new IllegalArgumentException("Provide at least one readable project, Jira, Swagger, document, or upload.");
        }
        String prompt = prompts.build(sourceContext, relevantMemory, userInstructions);
        JSONObject result = validator.parseAndValidate(model.invoke(prompt));
        JSONArray missing = missingApiOperations(sourceContext, result);
        if (!missing.isEmpty()) {
            String correction = prompt + "\n\nCOVERAGE CORRECTION REQUIRED:\n"
                    + "The previous result omitted these discovered API operations: " + missing + "\n"
                    + "Regenerate the complete JSON object. Include each exact HTTP method and path in its test cases "
                    + "and traceability. Previous result for correction:\n" + result.toString(2);
            result = validator.parseAndValidate(model.invoke(correction));
            missing = missingApiOperations(sourceContext, result);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Generated test plan is missing discovered API operations: " + missing);
            }
        }
        return result;
    }

    private JSONArray missingApiOperations(JSONObject sourceContext, JSONObject result) {
        JSONArray missing = new JSONArray();
        JSONArray testCases = result == null ? null : result.optJSONArray("testCases");
        JSONArray sources = sourceContext.optJSONArray("sources");
        if (sources == null) return missing;
        for (int i = 0; i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i);
            JSONArray operations = source == null ? null : source.optJSONArray("discoveredOperations");
            if (operations == null && source != null && source.optJSONObject("apiSummary") != null) {
                operations = source.getJSONObject("apiSummary").optJSONArray("operations");
            }
            if (operations == null) continue;
            for (int j = 0; j < operations.length(); j++) {
                JSONObject operation = operations.optJSONObject(j);
                if (operation == null) continue;
                String method = operation.optString("method").toLowerCase(java.util.Locale.ROOT);
                String path = operation.optString("path").toLowerCase(java.util.Locale.ROOT);
                boolean covered = false;
                for (int k = 0; testCases != null && k < testCases.length(); k++) {
                    JSONObject testCase = testCases.optJSONObject(k);
                    if (testCase == null || !method.equals(testCase.optString("httpMethod").toLowerCase(java.util.Locale.ROOT))
                            || !path.equals(testCase.optString("endpoint").toLowerCase(java.util.Locale.ROOT))) continue;
                    String baseUri = testCase.optString("baseUri");
                    String returnCode = testCase.optString("expectedReturnCode");
                    JSONArray allowedCodes = operation.optJSONArray("expectedReturnCodes");
                    boolean validCode = allowedCodes == null || allowedCodes.isEmpty();
                    for (int c = 0; allowedCodes != null && c < allowedCodes.length(); c++) {
                        if (allowedCodes.optString(c).equalsIgnoreCase(returnCode)) validCode = true;
                    }
                    if (!baseUri.isBlank() && !"N/A".equalsIgnoreCase(baseUri) && validCode) {
                        covered = true;
                        break;
                    }
                }
                if (!method.isBlank() && !path.isBlank() && !covered) {
                    missing.put(operation.optString("method") + " " + operation.optString("path"));
                }
            }
        }
        return missing;
    }
}

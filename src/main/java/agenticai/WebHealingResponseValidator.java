package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class WebHealingResponseValidator {
    private static final Set<String> SOURCES = Set.of("knowledgeBase", "crossSessionMemory", "projectMemory",
            "workflowMemory", "model");
    private static final Pattern VARIABLE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> EXPECTED_UPDATE_MODES = Set.of("none", "staticValue", "updateVariable");

    public JSONObject validate(JSONObject response, JSONObject context) {
        JSONObject payload = context.getJSONObject("payload");
        JSONArray steps = payload.getJSONArray("capturedSteps");
        Set<String> actions = new HashSet<>();
        JSONArray supported = payload.optJSONArray("supportedActions");
        if (supported != null) for (Object value : supported) actions.add(String.valueOf(value));
        JSONArray accepted = new JSONArray();
        JSONArray fixes = response == null ? null : response.optJSONArray("fixes");
        if (fixes == null) throw new IllegalArgumentException("Web Agent response must contain a fixes array.");
        fixes = new JSONArray(fixes.toString());
        appendUpstreamRuntimeEmailFix(fixes, payload, steps, actions);
        for (int i = 0; i < fixes.length(); i++) {
            JSONObject fix = fixes.optJSONObject(i);
            if (fix == null) continue;
            int index = resolveStepIndex(fix, steps);
            if (index < 0 || index >= steps.length()) continue;
            JSONObject current = steps.optJSONObject(index);
            if (current == null) continue;
            String stepName = current.optString("stepName");
            String action = first(fix.optString("action"), current.optString("action"));
            if (!actions.isEmpty() && !actions.contains(action)) continue;
            boolean expectedAction = expectedAction(action);
            double confidence = fix.has("confidence") ? fix.optDouble("confidence", -1) : 0.5;
            if (confidence < 0 || confidence > 1) continue;
            String source = first(fix.optString("resolutionSource"), "model");
            if (!SOURCES.contains(source)) continue;
            String variable = first(fix.optString("flowVariableName"), current.optString("flowVariableName"));
            if (!variable.isBlank() && !VARIABLE.matcher(variable).matches()) continue;
            String expectedVariable = expectedAction
                    ? first(fix.optString("expectedVariableName"), expectedVariable(current.optString("value"))) : "";
            String expectedUpdateMode = expectedAction ? first(fix.optString("expectedUpdateMode"), "none") : "none";
            if (!EXPECTED_UPDATE_MODES.contains(expectedUpdateMode)) expectedUpdateMode = "none";
            JSONObject savedVariables = payload.optJSONObject("savedVariables");
            String actualValue = fix.optString("actualValue");
            String currentExpected = current.optString("value");
            String resolvedExpected = expectedVariable.isBlank() || savedVariables == null
                    ? currentExpected : savedVariables.optString(expectedVariable, currentExpected);
            boolean expectedMismatch = expectedAction && !actualValue.isBlank()
                    && !resolvedExpected.equals(actualValue);
            if (expectedMismatch && "none".equals(expectedUpdateMode)) {
                expectedUpdateMode = !expectedVariable.isBlank() && savedVariables != null
                        && savedVariables.has(expectedVariable) ? "updateVariable" : "staticValue";
            }
            if ("updateVariable".equals(expectedUpdateMode)
                    && (expectedVariable.isBlank() || !VARIABLE.matcher(expectedVariable).matches()
                    || savedVariables == null || !savedVariables.has(expectedVariable))) {
                expectedUpdateMode = "staticValue";
            }
            JSONObject wait = fix.optJSONObject("waitSuggestion");
            if (wait != null) {
                int timeout = wait.optInt("timeoutMs", 0);
                if (timeout < 0 || timeout > 120000) continue;
            }
            accepted.put(new JSONObject(fix.toString())
                    .put("stepIndex", index)
                    .put("stepName", stepName)
                    .put("action", action)
                    .put("selector", fix.has("selector") ? fix.optString("selector") : current.optString("selector"))
                    .put("value", fix.has("value") ? fix.optString("value") : current.optString("value"))
                    .put("expectedValue", expectedAction
                            ? "staticValue".equals(expectedUpdateMode) && !actualValue.isBlank() ? actualValue
                            : fix.has("expectedValue") ? fix.optString("expectedValue") : currentExpected : "")
                    .put("actualValue", actualValue)
                    .put("expectedVariableName", expectedVariable)
                    .put("expectedUpdateMode", expectedUpdateMode)
                    .put("expectedVariableValue", expectedAction
                            ? first(fix.optString("expectedVariableValue"), actualValue) : "")
                    .put("note", fix.has("note") ? fix.optString("note") : current.optString("note"))
                    .put("flowVariableName", variable)
                    .put("confidence", confidence)
                    .put("resolutionSource", source));
        }
        return new JSONObject(response.toString()).put("agentUsed", "webAgent").put("fixes", accepted);
    }

    private void appendUpstreamRuntimeEmailFix(JSONArray fixes, JSONObject payload, JSONArray steps,
                                               Set<String> supportedActions) {
        if (!supportedActions.isEmpty() && !supportedActions.contains("Flow Variable")) return;
        String evidence = healingEvidence(fixes, payload.optJSONArray("failedResults"));
        boolean registrationFailure = evidence.contains("400") && evidence.contains("email")
                && (evidence.contains("unique") || evidence.contains("duplicate") || evidence.contains("static"));
        if (!registrationFailure) return;
        for (int index = 0; index < steps.length(); index++) {
            JSONObject step = steps.optJSONObject(index);
            if (step == null || !"flow variable".equals(step.optString("action").toLowerCase(Locale.ROOT))) continue;
            String variableName = first(step.optString("flowVariableName"), step.optString("selector"), step.optString("note"));
            if (!"email".equalsIgnoreCase(variableName) && !variableName.toLowerCase(Locale.ROOT).contains("email")) continue;
            String currentValue = step.optString("value");
            if (containsSupportedRuntimeVariable(currentValue, payload.optJSONArray("supportedRuntimeVariables"))) return;
            String replacement = uniqueRuntimeEmail(currentValue, payload.optJSONArray("supportedRuntimeVariables"));
            if (replacement.isBlank()) return;
            for (int i = 0; i < fixes.length(); i++) {
                JSONObject existing = fixes.optJSONObject(i);
                if (existing != null && (existing.optInt("stepIndex", -1) == index
                        || step.optString("stepName").equals(existing.optString("stepName")))) {
                    existing.put("stepIndex", index).put("stepName", step.optString("stepName"))
                            .put("action", "Flow Variable").put("selector", variableName)
                            .put("value", replacement).put("flowVariableName", variableName)
                            .put("variableCorrection", runtimeVariableCorrection(variableName, currentValue, replacement));
                    return;
                }
            }
            fixes.put(new JSONObject()
                    .put("stepIndex", index)
                    .put("stepName", step.optString("stepName"))
                    .put("failureType", "staticDuplicateData")
                    .put("cause", "Registration returned 400 while the email Flow Variable used a static value.")
                    .put("recommendedFix", "Update the existing email Flow Variable to a unique supported runtime value.")
                    .put("action", "Flow Variable")
                    .put("selector", variableName)
                    .put("value", replacement)
                    .put("expectedValue", "")
                    .put("actualValue", currentValue)
                    .put("expectedVariableName", "")
                    .put("expectedUpdateMode", "none")
                    .put("expectedVariableValue", "")
                    .put("note", step.optString("note"))
                    .put("flowVariableName", variableName)
                    .put("variableCorrection", runtimeVariableCorrection(variableName, currentValue, replacement))
                    .put("waitSuggestion", JSONObject.NULL)
                    .put("fallbackSelectors", new JSONArray())
                    .put("confidence", 0.95)
                    .put("resolutionSource", "model")
                    .put("contextSources", new JSONArray().put("failedResults").put("capturedSteps")
                            .put("supportedRuntimeVariables"))
                    .put("reasoningSummary", "Repair the upstream email data source before downstream page-state failures."));
            return;
        }
    }

    private JSONObject runtimeVariableCorrection(String name, String currentValue, String replacement) {
        return new JSONObject().put("name", name).put("currentValue", currentValue)
                .put("newValue", replacement).put("mode", "runtimeExpression");
    }

    private String healingEvidence(JSONArray fixes, JSONArray failures) {
        StringBuilder evidence = new StringBuilder();
        if (fixes != null) evidence.append(fixes.toString());
        if (failures != null) evidence.append(' ').append(failures);
        return evidence.toString().toLowerCase(Locale.ROOT);
    }

    private boolean containsSupportedRuntimeVariable(String value, JSONArray supported) {
        if (value == null || supported == null) return false;
        for (Object expression : supported) if (value.contains(String.valueOf(expression))) return true;
        return false;
    }

    private String uniqueRuntimeEmail(String currentValue, JSONArray supported) {
        String runtime = supportedExpression(supported, "randomInt");
        if (runtime.isBlank()) runtime = supportedExpression(supported, "randomString");
        if (runtime.isBlank()) runtime = supportedExpression(supported, "randomDate");
        if (runtime.isBlank()) return "";
        String value = currentValue == null ? "" : currentValue.trim();
        int at = value.lastIndexOf('@');
        String domain = at >= 0 && at + 1 < value.length() ? value.substring(at + 1) : "example.com";
        String local = at > 0 ? value.substring(0, at) : "user";
        String prefix = local.replaceAll("[0-9._+-]+$", "");
        if (prefix.isBlank()) prefix = "user";
        return prefix + runtime + "@" + domain;
    }

    private String supportedExpression(JSONArray supported, String name) {
        if (supported == null) return "";
        for (Object value : supported) {
            String expression = String.valueOf(value);
            if (expression.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT))) return expression;
        }
        return "";
    }

    private int resolveStepIndex(JSONObject fix, JSONArray steps) {
        String requestedName = fix.optString("stepName").trim();
        if (!requestedName.isBlank()) {
            int match = -1;
            int matches = 0;
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step != null && requestedName.equals(step.optString("stepName"))) {
                    matches++;
                    match = i;
                }
            }
            if (matches == 1) return match;
            int requestedIndex = fix.optInt("stepIndex", -1);
            JSONObject indexed = requestedIndex >= 0 && requestedIndex < steps.length()
                    ? steps.optJSONObject(requestedIndex) : null;
            return matches > 1 && indexed != null && requestedName.equals(indexed.optString("stepName"))
                    ? requestedIndex : -1;
        }
        return fix.optInt("stepIndex", -1);
    }

    private String first(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private String expectedVariable(String expression) {
        if (expression == null) return "";
        String value = expression.trim();
        if (value.startsWith("${") && value.endsWith("}")) return value.substring(2, value.length() - 1).trim();
        if (value.startsWith("{{") && value.endsWith("}}")) return value.substring(2, value.length() - 2).trim();
        return "";
    }

    private boolean expectedAction(String action) {
        String value = action == null ? "" : action.toLowerCase();
        return value.equals("validate text") || value.equals("wait for text") || value.equals("wait for url")
                || value.equals("assert url contains") || value.equals("visual compare")
                || value.equals("get text");
    }
}

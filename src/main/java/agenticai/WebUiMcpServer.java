package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * In-process MCP server facade for authoritative VeyraAI WebUI state. The UI
 * host can expose the same tool contract over a transport later without
 * changing the Web Agent's analysis contract.
 */
public final class WebUiMcpServer {
    private static final List<String> TOOLS = List.of(
            "webui.get_captured_steps",
            "webui.get_failed_results",
            "webui.get_variables_registry",
            "webui.get_failure_artifacts",
            "webui.search_historical_fixes",
            "webui.get_runtime_variables",
            "webui.build_analysis_context");

    public JSONObject expose(JSONObject context) {
        JSONObject payload = context.getJSONObject("payload");
        return new JSONObject()
                .put("server", new JSONObject()
                        .put("name", "veyra-webui-analysis-mcp")
                        .put("version", "1.0")
                        .put("mode", "in-process")
                        .put("readOnly", true))
                .put("tools", new JSONArray(TOOLS))
                .put("capturedSteps", payload.optJSONArray("capturedSteps"))
                .put("failedResults", payload.optJSONArray("failedResults"))
                .put("variablesRegistry", payload.optJSONObject("variablesRegistry"))
                .put("variableSources", payload.optJSONObject("variableSources"))
                .put("runtimeVariables", payload.optJSONArray("supportedRuntimeVariables"))
                .put("failureArtifacts", failureArtifacts(payload.optJSONArray("failedResults")))
                .put("historicalFixes", historicalFixes(context));
    }

    public JSONArray listTools() {
        return new JSONArray(TOOLS);
    }

    public Object callTool(String toolName, JSONObject context) {
        JSONObject exposed = expose(context);
        return switch (toolName == null ? "" : toolName) {
            case "webui.get_captured_steps" -> exposed.getJSONArray("capturedSteps");
            case "webui.get_failed_results" -> exposed.getJSONArray("failedResults");
            case "webui.get_variables_registry" -> exposed.getJSONObject("variablesRegistry");
            case "webui.get_failure_artifacts" -> exposed.getJSONArray("failureArtifacts");
            case "webui.search_historical_fixes" -> exposed.getJSONArray("historicalFixes");
            case "webui.get_runtime_variables" -> exposed.getJSONArray("runtimeVariables");
            case "webui.build_analysis_context" -> exposed;
            default -> throw new IllegalArgumentException("Unknown WebUI MCP tool: " + toolName);
        };
    }

    private JSONArray failureArtifacts(JSONArray failures) {
        JSONArray artifacts = new JSONArray();
        if (failures == null) return artifacts;
        for (int i = 0; i < failures.length(); i++) {
            JSONObject failure = failures.optJSONObject(i);
            if (failure == null) continue;
            artifacts.put(new JSONObject()
                    .put("stepIndex", failure.optInt("stepIndex", -1))
                    .put("stepName", failure.optString("stepName"))
                    .put("screenshotPath", failure.optString("screenshotPath"))
                    .put("pageUrl", failure.optString("pageUrl"))
                    .put("pageTitle", failure.optString("pageTitle"))
                    .put("ariaSnapshot", failure.optString("ariaSnapshot"))
                    .put("domSnapshot", failure.optString("domSnapshot"))
                    .put("consoleMessages", jsonArray(failure.optString("consoleMessages")))
                    .put("networkFailures", jsonArray(failure.optString("networkFailures"))));
        }
        return artifacts;
    }

    private JSONArray historicalFixes(JSONObject context) {
        JSONArray fixes = new JSONArray();
        append(fixes, context.optJSONArray("uiHealingKnowledge"), 24);
        append(fixes, context.optJSONArray("crossSessionMemory"), 36);
        return fixes;
    }

    private void append(JSONArray target, JSONArray source, int limit) {
        if (source == null) return;
        for (int i = 0; i < source.length() && target.length() < limit; i++) target.put(source.get(i));
    }

    private JSONArray jsonArray(String value) {
        try {
            return value == null || value.isBlank() ? new JSONArray() : new JSONArray(value);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }
}

package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Provides the Playwright MCP evidence contract consumed by the Web Agent. */
public final class PlaywrightMcpServer {
    private static final List<String> TOOLS = List.of(
            "browser_snapshot",
            "browser_take_screenshot",
            "browser_console_messages",
            "browser_network_requests",
            "browser_locator_validate",
            "browser_wait_for");

    public JSONObject expose(JSONObject context, JSONObject liveEvidence) {
        JSONArray capturedEvidence = new JSONArray();
        JSONArray failures = context.getJSONObject("payload").optJSONArray("failedResults");
        if (failures != null) {
            for (int i = 0; i < failures.length(); i++) {
                JSONObject failure = failures.optJSONObject(i);
                if (failure == null) continue;
                capturedEvidence.put(new JSONObject()
                        .put("stepIndex", failure.optInt("stepIndex", -1))
                        .put("stepName", failure.optString("stepName"))
                        .put("url", failure.optString("pageUrl"))
                        .put("title", failure.optString("pageTitle"))
                        .put("screenshotPath", failure.optString("screenshotPath"))
                        .put("ariaSnapshot", failure.optString("ariaSnapshot"))
                        .put("domSnapshot", failure.optString("domSnapshot"))
                        .put("consoleMessages", asArray(failure.optString("consoleMessages")))
                        .put("networkFailures", asArray(failure.optString("networkFailures"))));
            }
        }
        return new JSONObject()
                .put("server", new JSONObject()
                        .put("name", "playwright-mcp")
                        .put("version", "1.0")
                        .put("mode", "captured-failure-evidence+cdp-live")
                        .put("stateChangingToolsEnabled", false))
                .put("tools", new JSONArray(TOOLS))
                .put("capturedFailureEvidence", capturedEvidence)
                .put("liveBrowserEvidence", liveEvidence == null ? new JSONObject()
                        .put("available", false) : liveEvidence);
    }

    public JSONArray listTools() {
        return new JSONArray(TOOLS);
    }

    public Object callTool(String toolName, JSONObject context, JSONObject liveEvidence) {
        JSONObject exposed = expose(context, liveEvidence);
        JSONObject live = exposed.getJSONObject("liveBrowserEvidence");
        JSONArray captured = exposed.getJSONArray("capturedFailureEvidence");
        return switch (toolName == null ? "" : toolName) {
            case "browser_snapshot" -> live.optBoolean("available", false)
                    ? new JSONObject().put("ariaSnapshot", live.optString("ariaSnapshot"))
                    .put("domSnapshot", live.optString("domSnapshot")) : captured;
            case "browser_take_screenshot" -> live.optBoolean("available", false)
                    ? live.optString("screenshotPath") : screenshotPaths(captured);
            case "browser_console_messages" -> collectArrays(captured, "consoleMessages");
            case "browser_network_requests" -> collectArrays(captured, "networkFailures");
            case "browser_locator_validate" -> live.optJSONArray("locatorValidations") == null
                    ? new JSONArray() : live.getJSONArray("locatorValidations");
            case "browser_wait_for" -> throw new IllegalStateException(
                    "State-changing Playwright MCP tools are disabled during analysis.");
            default -> throw new IllegalArgumentException("Unknown Playwright MCP tool: " + toolName);
        };
    }

    private JSONArray screenshotPaths(JSONArray captured) {
        JSONArray paths = new JSONArray();
        for (int i = 0; i < captured.length(); i++) {
            String path = captured.getJSONObject(i).optString("screenshotPath");
            if (!path.isBlank()) paths.put(path);
        }
        return paths;
    }

    private JSONArray collectArrays(JSONArray captured, String key) {
        JSONArray values = new JSONArray();
        for (int i = 0; i < captured.length(); i++) {
            JSONArray source = captured.getJSONObject(i).optJSONArray(key);
            if (source != null) for (Object value : source) values.put(value);
        }
        return values;
    }

    private JSONArray asArray(String value) {
        try {
            return value == null || value.isBlank() ? new JSONArray() : new JSONArray(value);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }
}

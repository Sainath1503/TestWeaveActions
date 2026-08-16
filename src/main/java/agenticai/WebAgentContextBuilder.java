package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebAgentContextBuilder {
    private final SharedAgentContextService sharedContext;

    public WebAgentContextBuilder(SharedAgentContextService sharedContext) {
        this.sharedContext = sharedContext;
    }

    public JSONObject build(String workspaceId, String projectId, String workflowId, String sessionId,
                            JSONObject payload, JSONArray apiKnowledge, JSONArray dbKnowledge,
                            JSONArray uiKnowledge, JSONArray crossSessionMemory) {
        if (payload == null || payload.optJSONArray("capturedSteps") == null) {
            throw new IllegalArgumentException("Captured step context is unavailable.");
        }
        JSONArray failures = payload.optJSONArray("failedResults");
        if (failures == null || failures.isEmpty()) {
            throw new IllegalArgumentException("No failed Web test steps were found.");
        }
        enrichPayload(payload);
        return sharedContext.buildWebContext(workspaceId, projectId, workflowId, sessionId, payload,
                apiKnowledge, dbKnowledge, uiKnowledge, crossSessionMemory);
    }

    private void enrichPayload(JSONObject payload) {
        try {
            URI uri = URI.create(payload.optString("startUrl"));
            payload.put("normalizedDomain", uri.getHost() == null ? "" : uri.getHost().toLowerCase());
            payload.put("normalizedPath", uri.getPath() == null ? "" : uri.getPath());
        } catch (Exception ignored) {
            payload.put("normalizedDomain", "").put("normalizedPath", "");
        }
        JSONArray steps = payload.getJSONArray("capturedSteps");
        JSONArray failures = payload.getJSONArray("failedResults");
        for (int i = 0; i < failures.length(); i++) {
            JSONObject failure = failures.optJSONObject(i);
            if (failure == null) continue;
            int index = failure.optInt("stepIndex", -1);
            JSONObject step = index >= 0 && index < steps.length() ? steps.optJSONObject(index) : null;
            if (step == null) continue;
            failure.put("stepName", step.optString("stepName"))
                    .put("currentAction", step.optString("action"))
                    .put("currentSelector", step.optString("selector"))
                    .put("currentValue", step.optString("value"))
                    .put("note", step.optString("note"))
                    .put("flowVariableName", step.optString("flowVariableName"))
                    .put("actualValue", actualValue(failure.optString("message")))
                    .put("expectedValue", failure.optString("expected", step.optString("value")));
        }
    }

    public String actualValue(String message) {
        if (message == null) return "";
        Matcher quoted = Pattern.compile("(?i)(?:value|text)='([^']*)'").matcher(message);
        String last = "";
        while (quoted.find()) last = quoted.group(1);
        if (!last.isBlank()) return last;
        Matcher actual = Pattern.compile("(?i)actual(?: value| text)?\\s*[:=]\\s*['\"]?([^'\"|]+)").matcher(message);
        if (actual.find()) return actual.group(1).trim();
        int marker = message.toLowerCase().indexOf("actual");
        return marker < 0 ? "" : message.substring(marker).replaceFirst("(?i)^actual\\s*[:=]?\\s*", "").trim();
    }
}

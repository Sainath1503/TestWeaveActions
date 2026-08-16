package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;

public final class WebAgentKnowledgeService {
    public JSONObject approvedFix(JSONObject context, JSONObject originalStep, JSONObject failedResult,
                                  JSONObject finalFix, String failureSignature) {
        return new JSONObject()
                .put("level", "knowledgeBase")
                .put("source", "uiStudio")
                .put("sourceAgent", "webAgent")
                .put("type", memoryType(finalFix))
                .put("workspaceId", context.optString("workspaceId"))
                .put("projectId", context.optString("projectId"))
                .put("workflowId", context.optString("workflowId"))
                .put("approvedByUser", true)
                .put("confidence", finalFix.optDouble("confidence", 1.0))
                .put("content", new JSONObject()
                        .put("sourceAgent", "webAgent")
                        .put("testName", context.optString("testName"))
                        .put("startUrl", context.optString("startUrl"))
                        .put("failureSignature", failureSignature)
                        .put("originalStep", originalStep)
                        .put("failedResult", failedResult)
                        .put("approvedCorrection", finalFix)
                        .put("contextSources", finalFix.optJSONArray("contextSources")))
                .put("tags", tags(finalFix))
                .put("createdAt", Instant.now().toString());
    }

    private String memoryType(JSONObject fix) {
        if (fix.optJSONObject("waitSuggestion") != null) return "acceptedWaitSuggestion";
        if ("updateVariable".equals(fix.optString("expectedUpdateMode"))) return "acceptedVariableCorrection";
        if (fix.optJSONObject("variableCorrection") != null) return "acceptedVariableCorrection";
        if (!fix.optString("flowVariableName").isBlank()) return "acceptedFlowVariableCorrection";
        if (fix.optString("failureType").toLowerCase().contains("expected")) return "acceptedExpectedValueCorrection";
        if (fix.optString("failureType").toLowerCase().contains("action")) return "acceptedActionCorrection";
        return "acceptedLocatorHealing";
    }

    private JSONArray tags(JSONObject fix) {
        return new JSONArray().put("webAgent").put("uiHealing").put(fix.optString("failureType", "scriptFailure"));
    }
}

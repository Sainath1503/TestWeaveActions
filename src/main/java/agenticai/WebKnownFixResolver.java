package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

public final class WebKnownFixResolver {
    private final WebFailureSignatureService signatures;
    private final double threshold;

    public WebKnownFixResolver(WebFailureSignatureService signatures) {
        this(signatures, Double.parseDouble(System.getProperty("veyraai.web.knownFixThreshold", "0.90")));
    }

    public WebKnownFixResolver(WebFailureSignatureService signatures, double threshold) {
        this.signatures = signatures;
        this.threshold = threshold;
    }

    public Resolution resolve(JSONObject context) {
        JSONObject payload = context.getJSONObject("payload");
        JSONArray failures = payload.optJSONArray("failedResults");
        JSONArray steps = payload.optJSONArray("capturedSteps");
        Map<String, JSONObject> approved = approvedBySignature(context);
        JSONArray known = new JSONArray();
        JSONArray unresolved = new JSONArray();
        if (failures == null) return new Resolution(known, unresolved);
        for (int i = 0; i < failures.length(); i++) {
            JSONObject failure = failures.optJSONObject(i);
            if (failure == null) continue;
            int stepIndex = failure.optInt("stepIndex", -1);
            JSONObject step = stepIndex >= 0 && steps != null && stepIndex < steps.length()
                    ? steps.optJSONObject(stepIndex) : null;
            if (step == null) {
                unresolved.put(failure);
                continue;
            }
            String signature = signatures.signature(scope(context, payload), step, failure);
            JSONObject entry = approved.get(signature);
            if (entry == null) {
                unresolved.put(new JSONObject(failure.toString()).put("failureSignature", signature));
                continue;
            }
            JSONObject content = entry.optJSONObject("content");
            JSONObject fix = content == null ? null : content.optJSONObject("approvedCorrection");
            if (fix == null) fix = entry.optJSONObject("approvedCorrection");
            if (fix == null) {
                unresolved.put(failure);
                continue;
            }
            known.put(new JSONObject(fix.toString())
                    .put("stepIndex", stepIndex)
                    .put("confidence", Math.max(threshold, entry.optDouble("confidence", threshold)))
                    .put("resolutionSource", "knowledgeBase")
                    .put("knowledgeEntryId", entry.optString("id"))
                    .put("failureSignature", signature)
                    .put("contextSources", new JSONArray().put("webHealingKnowledge")));
        }
        return new Resolution(known, unresolved);
    }

    private Map<String, JSONObject> approvedBySignature(JSONObject context) {
        Map<String, JSONObject> entries = new HashMap<>();
        collect(entries, context.optJSONArray("uiHealingKnowledge"));
        collect(entries, context.optJSONArray("crossSessionMemory"));
        return entries;
    }

    private void collect(Map<String, JSONObject> entries, JSONArray values) {
        if (values == null) return;
        for (int i = 0; i < values.length(); i++) {
            JSONObject entry = values.optJSONObject(i);
            if (entry == null || entry.optDouble("confidence", 0) < threshold) continue;
            JSONObject content = entry.optJSONObject("content");
            String signature = content == null ? entry.optString("failureSignature") : content.optString("failureSignature");
            if (!signature.isBlank()) entries.putIfAbsent(signature, entry);
        }
    }

    private JSONObject scope(JSONObject context, JSONObject payload) {
        return new JSONObject()
                .put("projectId", context.optString("projectId"))
                .put("workflowId", context.optString("workflowId"))
                .put("testName", payload.optString("testName"))
                .put("startUrl", payload.optString("startUrl"));
    }

    public record Resolution(JSONArray knownFixes, JSONArray unresolvedFailures) {}
}

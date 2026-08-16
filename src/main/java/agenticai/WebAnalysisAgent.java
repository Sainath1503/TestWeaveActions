package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

public final class WebAnalysisAgent {
    @FunctionalInterface
    public interface ModelInvoker { String invoke(String prompt) throws Exception; }

    private final WebKnownFixResolver knownFixes;
    private final WebAgentPromptBuilder prompts;
    private final WebHealingResponseValidator validator;

    public WebAnalysisAgent(WebKnownFixResolver knownFixes, WebAgentPromptBuilder prompts,
                            WebHealingResponseValidator validator) {
        this.knownFixes = knownFixes;
        this.prompts = prompts;
        this.validator = validator;
    }

    public JSONObject analyze(JSONObject context, ModelInvoker model) throws Exception {
        WebKnownFixResolver.Resolution resolution = knownFixes.resolve(context);
        JSONArray merged = new JSONArray();
        for (Object fix : resolution.knownFixes()) merged.put(fix);
        boolean modelCalled = !resolution.unresolvedFailures().isEmpty();
        if (modelCalled) {
            JSONObject modelContext = new JSONObject(context.toString());
            JSONObject payload = new JSONObject(modelContext.getJSONObject("payload").toString())
                    .put("failedResults", resolution.unresolvedFailures());
            modelContext.put("payload", payload);
            JSONObject modelResponse = parseObject(model.invoke(prompts.build(modelContext)));
            JSONObject validated = validator.validate(modelResponse, context);
            for (Object fix : validated.getJSONArray("fixes")) merged.put(fix);
        }
        return validator.validate(new JSONObject()
                .put("agentUsed", "webAgent")
                .put("modelCalled", modelCalled)
                .put("knownFixesUsed", resolution.knownFixes().length())
                .put("fixes", merged), context);
    }

    private JSONObject parseObject(String output) {
        String text = output == null ? "" : output.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < start) throw new IllegalArgumentException("Web Agent did not return a JSON object.");
        return new JSONObject(text.substring(start, end + 1));
    }
}

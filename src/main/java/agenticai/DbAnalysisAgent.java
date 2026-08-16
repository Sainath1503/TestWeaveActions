package agenticai;

import org.json.JSONObject;

public final class DbAnalysisAgent {
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(String prompt) throws Exception;
    }

    private final DbAgentPromptBuilder prompts;

    public DbAnalysisAgent(DbAgentPromptBuilder prompts) {
        this.prompts = prompts;
    }

    public String analyze(JSONObject context, ModelInvoker model) throws Exception {
        if (context == null || context.optJSONObject("databaseContext") == null) {
            throw new IllegalArgumentException("Select a DB table or provide query results before requesting validation suggestions.");
        }
        return model.invoke(prompts.build(context));
    }
}

package agenticai;

import org.json.JSONObject;

import java.time.Instant;

public final class DbSuggestionKnowledgeService {
    public JSONObject approvedValidation(JSONObject suggestion, String workspaceId, String projectId) {
        return base(suggestion, "dbValidationRule", workspaceId, projectId)
                .put("table", suggestion.optString("table"))
                .put("column", suggestion.optString("dbColumnName", suggestion.optString("column")))
                .put("rule", suggestion.optString("rule"))
                .put("expected", suggestion.opt("expected"))
                .put("tags", suggestion.optJSONArray("tags"));
    }

    public JSONObject approvedMapping(JSONObject suggestion, String workspaceId, String projectId) {
        return base(suggestion, "apiDbMapping", workspaceId, projectId)
                .put("apiJsonPath", suggestion.optString("jsonPath", suggestion.optString("apiField")))
                .put("dbTable", suggestion.optString("dbTable", suggestion.optString("table")))
                .put("dbColumn", suggestion.optString("dbColumn"))
                .put("transformation", suggestion.optString("transformation"))
                .put("tags", suggestion.optJSONArray("tags"));
    }

    private JSONObject base(JSONObject suggestion, String type, String workspaceId, String projectId) {
        return new JSONObject(suggestion.toString())
                .put("type", type)
                .put("sourceAgent", "dbAgent")
                .put("workspaceId", workspaceId)
                .put("projectId", projectId)
                .put("approvedByUser", true)
                .put("createdAt", Instant.now().toString());
    }
}

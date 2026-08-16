package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

public final class DbAgentContextBuilder {
    private final SharedAgentContextService sharedContext;

    public DbAgentContextBuilder(SharedAgentContextService sharedContext) {
        this.sharedContext = sharedContext;
    }

    public JSONObject build(String workspaceId, String projectId, String sessionId,
                            JSONObject databaseContext, JSONObject apiContext,
                            JSONArray knowledge, JSONArray mappings) {
        return sharedContext.buildDbContext(workspaceId, projectId, sessionId, databaseContext,
                apiContext, knowledge, mappings);
    }
}

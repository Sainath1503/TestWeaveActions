package agenticai.testplanning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Memory transformation tools; persistence remains owned by the host's Local/Firebase repository. */
public final class TestPlanningMemoryMcpServer {
    public JSONObject describe() {
        return new JSONObject()
                .put("server", new JSONObject().put("name", "veyra-test-planning-memory-mcp")
                        .put("version", "1.0").put("mode", "host-storage").put("projectScoped", true))
                .put("tools", new JSONArray().put("memory.search").put("memory.remember")
                        .put("memory.forget_project").put("memory.promote_knowledge"));
    }

    public JSONArray selectRelevant(JSONArray available, String query, int limit) {
        JSONArray selected = new JSONArray();
        Set<String> terms = terms(query);
        if (available == null) return selected;
        for (int i = 0; i < available.length() && selected.length() < Math.max(0, limit); i++) {
            Object value = available.get(i);
            String text = String.valueOf(value).toLowerCase(Locale.ROOT);
            if (terms.isEmpty() || terms.stream().anyMatch(text::contains)) selected.put(value);
        }
        return selected;
    }

    public JSONArray knowledgeFrom(JSONObject result) {
        JSONArray knowledge = result == null ? null : result.optJSONArray("memory");
        return knowledge == null ? new JSONArray() : knowledge;
    }

    public JSONObject persistentRecord(JSONObject item, String projectId, JSONArray sourceIds) {
        return new JSONObject()
                .put("projectId", projectId == null ? "" : projectId)
                .put("type", item.optString("type", "domainFact"))
                .put("summary", item.optString("summary"))
                .put("content", item.optJSONObject("content") == null ? new JSONObject() : item.getJSONObject("content"))
                .put("tags", item.optJSONArray("tags") == null ? new JSONArray() : item.getJSONArray("tags"))
                .put("sourceIds", sourceIds == null ? new JSONArray() : sourceIds)
                .put("confidence", Math.max(0.0, Math.min(1.0, item.optDouble("confidence", 0.7))));
    }

    private Set<String> terms(String query) {
        Set<String> values = new LinkedHashSet<>();
        if (query == null) return values;
        for (String token : query.toLowerCase(Locale.ROOT).split("[^a-z0-9_.-]+")) {
            if (token.length() >= 3) values.add(token);
        }
        return values;
    }
}

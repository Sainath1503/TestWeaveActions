package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SharedAgentContextService {
    public JSONObject buildDbContext(String workspaceId, String projectId, String sessionId,
                                     JSONObject databaseContext, JSONObject apiContext,
                                     JSONArray approvedKnowledge, JSONArray previousMappings) {
        Set<String> relevanceTerms = relevanceTerms(databaseContext, apiContext);
        return new JSONObject()
                .put("source", "dbStudio")
                .put("action", "suggestDbValidations")
                .put("workspaceId", workspaceId)
                .put("projectId", projectId)
                .put("sessionId", sessionId)
                .put("databaseContext", databaseContext)
                .put("apiContext", apiContext)
                .put("relevantKnowledge", filterRelevant(approvedKnowledge, relevanceTerms, 20))
                .put("previousAcceptedMappings", filterRelevant(previousMappings, relevanceTerms, 12));
    }

    public JSONObject buildWebContext(String workspaceId, String projectId, String workflowId, String sessionId,
                                      JSONObject payload, JSONArray apiKnowledge, JSONArray dbKnowledge,
                                      JSONArray uiHealingKnowledge, JSONArray crossSessionMemory) {
        Set<String> terms = relevanceTerms(payload);
        return new JSONObject()
                .put("agent", "webAgent")
                .put("source", "uiStudio")
                .put("action", "healFailedScript")
                .put("workspaceId", workspaceId)
                .put("projectId", projectId)
                .put("workflowId", workflowId)
                .put("sessionId", sessionId)
                .put("payload", payload)
                .put("apiKnowledge", deduplicate(filterRelevant(apiKnowledge, terms, 10), 10))
                .put("dbKnowledge", deduplicate(filterRelevant(dbKnowledge, terms, 10), 10))
                .put("uiHealingKnowledge", deduplicate(filterRelevant(uiHealingKnowledge, terms, 16), 16))
                .put("crossSessionMemory", deduplicate(filterRelevant(crossSessionMemory, terms, 12), 12));
    }

    public JSONArray filterRelevant(JSONArray entries, Set<String> terms, int limit) {
        JSONArray filtered = new JSONArray();
        if (entries == null) {
            return filtered;
        }
        for (Object value : entries) {
            if (filtered.length() >= limit) {
                break;
            }
            String text = String.valueOf(value).toLowerCase(Locale.ROOT);
            boolean relevant = terms.isEmpty() || terms.stream().anyMatch(text::contains);
            if (relevant) {
                filtered.put(value);
            }
        }
        return filtered;
    }

    private JSONArray deduplicate(JSONArray entries, int limit) {
        Map<String, Object> unique = new LinkedHashMap<>();
        if (entries != null) {
            for (Object value : entries) {
                String key = value instanceof JSONObject object
                        ? object.optString("id", object.toString()) : String.valueOf(value);
                unique.putIfAbsent(key, value);
                if (unique.size() >= limit) break;
            }
        }
        return new JSONArray(unique.values());
    }

    private Set<String> relevanceTerms(JSONObject... values) {
        Set<String> terms = new LinkedHashSet<>();
        if (values != null) {
            for (JSONObject value : values) addTerms(terms, value);
        }
        return terms;
    }

    private Set<String> relevanceTerms(JSONObject databaseContext, JSONObject apiContext) {
        return relevanceTerms(new JSONObject[]{databaseContext, apiContext});
    }

    private void addTerms(Set<String> terms, JSONObject value) {
        if (value == null) {
            return;
        }
        for (String token : value.toString().toLowerCase(Locale.ROOT).split("[^a-z0-9_$.]+")) {
            if (token.length() >= 3) {
                terms.add(token);
            }
        }
    }
}

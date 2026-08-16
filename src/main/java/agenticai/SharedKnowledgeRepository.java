package agenticai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SharedKnowledgeRepository {
    private final Supplier<JSONArray> loader;
    private final Consumer<JSONObject> saver;
    private final SharedAgentContextService contextService;

    public SharedKnowledgeRepository(Supplier<JSONArray> loader, Consumer<JSONObject> saver,
                                     SharedAgentContextService contextService) {
        this.loader = loader;
        this.saver = saver;
        this.contextService = contextService;
    }

    public JSONArray search(JSONObject query, int limit) {
        return contextService.filterRelevant(loader.get(), queryTerms(query), limit);
    }

    public void saveApproved(JSONObject knowledge) {
        if (!knowledge.optBoolean("approvedByUser", false)) {
            throw new IllegalArgumentException("Only explicitly approved knowledge can be saved.");
        }
        saver.accept(knowledge);
    }

    private java.util.Set<String> queryTerms(JSONObject query) {
        java.util.Set<String> terms = new java.util.LinkedHashSet<>();
        for (String token : query.toString().toLowerCase().split("[^a-z0-9_$.]+")) {
            if (token.length() >= 3) terms.add(token);
        }
        return terms;
    }
}

package agenticai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AgentRegistry {
    @FunctionalInterface
    public interface Initializer {
        void initialize(String sessionId) throws Exception;
    }

    private final Map<String, Initializer> initializers = new LinkedHashMap<>();

    public void register(String agentId, Initializer initializer) {
        initializers.put(agentId, initializer);
    }

    public RegistrationResult initializeAll(String sessionId) {
        Map<String, String> failures = new LinkedHashMap<>();
        for (Map.Entry<String, Initializer> entry : initializers.entrySet()) {
            try {
                entry.getValue().initialize(sessionId);
            } catch (Exception exception) {
                failures.put(entry.getKey(), exception.getMessage());
            }
        }
        List<String> connected = initializers.keySet().stream()
                .filter(id -> !failures.containsKey(id))
                .toList();
        return new RegistrationResult(connected, failures);
    }

    public record RegistrationResult(List<String> connectedAgents, Map<String, String> failures) {
        public boolean allConnected() {
            return failures.isEmpty();
        }
    }
}

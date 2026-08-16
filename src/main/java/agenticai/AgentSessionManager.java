package agenticai;

import java.time.Instant;
import java.util.List;

public final class AgentSessionManager {
    private volatile AgentSession activeSession;

    public AgentSession activate(String sessionId, String provider, String connectionMode,
                                 List<String> connectedAgents) {
        String now = Instant.now().toString();
        activeSession = new AgentSession(sessionId, provider, "connected", connectionMode,
                List.copyOf(connectedAgents), now, now);
        return activeSession;
    }

    public AgentSession touch() {
        AgentSession current = activeSession;
        if (current != null) {
            activeSession = new AgentSession(current.sessionId(), current.provider(), current.status(),
                    current.connectionMode(), current.connectedAgents(), current.createdAt(), Instant.now().toString());
        }
        return activeSession;
    }

    public AgentSession getActiveSession() {
        return activeSession;
    }

    public void disconnect() {
        activeSession = null;
    }

    public record AgentSession(String sessionId, String provider, String status, String connectionMode,
                               List<String> connectedAgents, String createdAt, String lastUsedAt) {
    }
}

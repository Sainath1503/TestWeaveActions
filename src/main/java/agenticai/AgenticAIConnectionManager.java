package agenticai;

import java.util.List;

public final class AgenticAIConnectionManager {
    private final AgentSessionManager sessions;
    private final AgentRegistry registry;

    public AgenticAIConnectionManager(AgentSessionManager sessions, AgentRegistry registry) {
        this.sessions = sessions;
        this.registry = registry;
    }

    public ConnectionResult connectNewSession(String provider, String sessionId) {
        return connect(provider, sessionId, "new");
    }

    public ConnectionResult connectExistingSession(String provider, String sessionId) {
        return connect(provider, sessionId, "existing");
    }

    private ConnectionResult connect(String provider, String sessionId, String mode) {
        AgentRegistry.RegistrationResult registration = registry.initializeAll(sessionId);
        AgentSessionManager.AgentSession session = sessions.activate(sessionId, provider, mode,
                registration.connectedAgents());
        return new ConnectionResult(session, registration);
    }

    public void disconnectSession() {
        sessions.disconnect();
    }

    public AgentSessionManager.AgentSession getActiveSession() {
        return sessions.getActiveSession();
    }

    public List<String> getConnectedAgents() {
        AgentSessionManager.AgentSession session = sessions.getActiveSession();
        return session == null ? List.of() : session.connectedAgents();
    }

    public record ConnectionResult(AgentSessionManager.AgentSession session,
                                   AgentRegistry.RegistrationResult registration) {
        public boolean fullyConnected() {
            return registration.allConnected()
                    && registration.connectedAgents().containsAll(List.of("apiAgent", "dbAgent", "webAgent"));
        }
    }
}

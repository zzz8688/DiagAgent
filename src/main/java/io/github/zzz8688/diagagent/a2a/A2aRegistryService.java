package io.github.zzz8688.diagagent.a2a;

import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnection;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnectionState;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

@Service
public class A2aRegistryService {

    private final ConcurrentMap<String, A2aRemoteAgentRegistration> agents = new ConcurrentHashMap<>();

    public void registerAgent(A2aRemoteAgentRegistration registration) {
        if (registration == null || registration.agentId() == null || registration.agentId().isBlank()) {
            return;
        }
        agents.put(registration.agentId(), registration);
    }

    public Optional<A2aRemoteAgentRegistration> findAgent(String agentId) {
        return Optional.ofNullable(agents.get(agentId));
    }

    public Optional<A2aAgentCard> selfAgentCard() {
        return agents.values().stream()
                .filter(A2aRemoteAgentRegistration::localAgent)
                .map(A2aRemoteAgentRegistration::agentCard)
                .findFirst();
    }

    public List<A2aRemoteAgentRegistration> listRefreshableAgents() {
        return sortedAgents().stream()
                .filter(agent -> !agent.localAgent())
                .filter(agent -> agent.serverConfig() != null && agent.serverConfig().enabled())
                .filter(agent -> agent.serverConfig().endpoint() != null && !agent.serverConfig().endpoint().isBlank())
                .toList();
    }

    public A2aRegistrySnapshot snapshot() {
        return new A2aRegistrySnapshot(sortedAgents());
    }

    public A2aCliStateSnapshot cliState() {
        List<A2aCliStateSnapshot.AgentState> serializedAgents = sortedAgents().stream()
                .map(agent -> new A2aCliStateSnapshot.AgentState(
                        agent.agentId(),
                        agent.agentName(),
                        agent.localAgent(),
                        agent.status().name(),
                        agent.serverConfig() == null ? "" : nullSafe(agent.serverConfig().endpoint()),
                        agent.transportSpec() == null || agent.transportSpec().transportType() == null
                                ? ""
                                : agent.transportSpec().transportType().name(),
                        agent.transportConnection() == null || agent.transportConnection().state() == null
                                ? ""
                                : agent.transportConnection().state().name(),
                        agent.agentCard() == null || agent.agentCard().skills() == null ? 0 : agent.agentCard().skills().size()
                ))
                .toList();
        return new A2aCliStateSnapshot(serializedAgents);
    }

    public void markDiscoverySucceeded(String agentId, A2aAgentCard agentCard) {
        updateAgent(agentId, current -> current.withAgentCard(
                agentCard,
                nextConnection(current, ProtocolTransportConnectionState.CONNECTED, null),
                current.localAgent() ? A2aRemoteAgentStatus.LOCAL : A2aRemoteAgentStatus.DISCOVERED,
                null,
                Instant.now().toString()
        ));
    }

    public void markDiscoveryStarted(String agentId) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.CONNECTING, null),
                current.localAgent() ? A2aRemoteAgentStatus.LOCAL : current.status(),
                null,
                Instant.now().toString()
        ));
    }

    public void markDiscoveryFailed(String agentId, String errorMessage) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.DEGRADED, errorMessage),
                current.localAgent() ? A2aRemoteAgentStatus.DEGRADED : A2aRemoteAgentStatus.DEGRADED,
                errorMessage,
                Instant.now().toString()
        ));
    }

    public void markInvocationSucceeded(String agentId) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.CONNECTED, null),
                current.localAgent() ? A2aRemoteAgentStatus.LOCAL : A2aRemoteAgentStatus.DISCOVERED,
                null,
                Instant.now().toString()
        ));
    }

    public void markInvocationFailed(String agentId, String errorMessage) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.FAILED, errorMessage),
                current.localAgent() ? A2aRemoteAgentStatus.DEGRADED : A2aRemoteAgentStatus.FAILED,
                errorMessage,
                Instant.now().toString()
        ));
    }

    public void markStreamingStarted(String agentId) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.CONNECTING, null),
                current.localAgent() ? A2aRemoteAgentStatus.LOCAL : current.status(),
                null,
                Instant.now().toString()
        ));
    }

    public void markStreamingSucceeded(String agentId) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.CONNECTED, null),
                current.localAgent() ? A2aRemoteAgentStatus.LOCAL : A2aRemoteAgentStatus.DISCOVERED,
                null,
                Instant.now().toString()
        ));
    }

    public void markStreamingFailed(String agentId, String errorMessage) {
        updateAgent(agentId, current -> current.withConnection(
                nextConnection(current, ProtocolTransportConnectionState.DEGRADED, errorMessage),
                current.localAgent() ? A2aRemoteAgentStatus.DEGRADED : A2aRemoteAgentStatus.DEGRADED,
                errorMessage,
                Instant.now().toString()
        ));
    }

    private List<A2aRemoteAgentRegistration> sortedAgents() {
        return agents.values().stream()
                .sorted(Comparator.comparing(A2aRemoteAgentRegistration::agentId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private void updateAgent(String agentId, UnaryOperator<A2aRemoteAgentRegistration> updater) {
        if (agentId == null || agentId.isBlank() || updater == null) {
            return;
        }
        agents.computeIfPresent(agentId, (ignored, current) -> updater.apply(current));
    }

    private ProtocolTransportConnection nextConnection(A2aRemoteAgentRegistration current,
                                                       ProtocolTransportConnectionState state,
                                                       String errorMessage) {
        if (current.transportConnection() == null) {
            return null;
        }
        String now = Instant.now().toString();
        String handshakeAt = state == ProtocolTransportConnectionState.CONNECTED ? now : null;
        return current.transportConnection().withState(state, now, handshakeAt, errorMessage);
    }
}

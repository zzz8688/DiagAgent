package io.github.zzz8688.diagagent.a2a;

import io.github.zzz8688.diagagent.protocol.ProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnection;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportSpec;

public record A2aRemoteAgentRegistration(
        String agentId,
        String agentName,
        boolean localAgent,
        A2aRemoteAgentStatus status,
        ProtocolServerConfig serverConfig,
        ProtocolTransportSpec transportSpec,
        ProtocolTransportConnection transportConnection,
        A2aAgentCard agentCard,
        String lastError,
        String lastSeenAt
) {

    public A2aRemoteAgentRegistration withStatus(A2aRemoteAgentStatus nextStatus, String error, String seenAt) {
        return new A2aRemoteAgentRegistration(
                agentId,
                agentName,
                localAgent,
                nextStatus,
                serverConfig,
                transportSpec,
                transportConnection,
                agentCard,
                error,
                seenAt
        );
    }

    public A2aRemoteAgentRegistration withConnection(ProtocolTransportConnection nextConnection,
                                                     A2aRemoteAgentStatus nextStatus,
                                                     String error,
                                                     String seenAt) {
        return new A2aRemoteAgentRegistration(
                agentId,
                agentName,
                localAgent,
                nextStatus,
                serverConfig,
                transportSpec,
                nextConnection,
                agentCard,
                error,
                seenAt
        );
    }

    public A2aRemoteAgentRegistration withAgentCard(A2aAgentCard nextAgentCard,
                                                    ProtocolTransportConnection nextConnection,
                                                    A2aRemoteAgentStatus nextStatus,
                                                    String error,
                                                    String seenAt) {
        return new A2aRemoteAgentRegistration(
                agentId,
                agentName,
                localAgent,
                nextStatus,
                serverConfig,
                transportSpec,
                nextConnection,
                nextAgentCard,
                error,
                seenAt
        );
    }
}

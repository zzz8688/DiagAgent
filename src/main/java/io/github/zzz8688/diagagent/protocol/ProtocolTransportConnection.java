package io.github.zzz8688.diagagent.protocol;

import io.github.zzz8688.diagagent.mcp.McpTransportType;

import java.util.Map;

public record ProtocolTransportConnection(
        String connectionId,
        String serverId,
        McpTransportType transportType,
        String endpoint,
        String command,
        ProtocolTransportConnectionState state,
        boolean authRequired,
        boolean reconnectSupported,
        boolean cleanupSupported,
        String createdAt,
        String lastStateChangedAt,
        String lastHandshakeAt,
        String lastError,
        Map<String, Object> metadata
) {

    public ProtocolTransportConnection {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public ProtocolTransportConnection withState(ProtocolTransportConnectionState nextState,
                                                 String stateChangedAt,
                                                 String handshakeAt,
                                                 String errorMessage) {
        return new ProtocolTransportConnection(
                connectionId,
                serverId,
                transportType,
                endpoint,
                command,
                nextState,
                authRequired,
                reconnectSupported,
                cleanupSupported,
                createdAt,
                stateChangedAt,
                handshakeAt == null ? lastHandshakeAt : handshakeAt,
                errorMessage,
                metadata
        );
    }
}

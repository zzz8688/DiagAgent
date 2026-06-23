package io.github.zzz8688.diagagent.mcp;

import io.github.zzz8688.diagagent.protocol.ProtocolServerConfig;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnection;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnectionState;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportSpec;

public record McpServerRegistration(
        String serverId,
        String serverName,
        String protocolType,
        McpTransportType transportType,
        String authType,
        String endpoint,
        boolean enabled,
        McpServerStatus status,
        String lastConnectedAt,
        String lastUpdatedAt,
        String lastError,
        int discoveredToolCount,
        int discoveredResourceCount,
        int discoveredPromptCount,
        String lastCapabilityRefreshAt,
        String capabilityVersion,
        String capabilityDigest,
        Integer reconnectAttempt,
        Integer maxReconnectAttempts,
        String nextRetryAt,
        ProtocolTransportSpec transportSpec,
        ProtocolTransportConnection transportConnection
) {

    public static McpServerRegistration fromConfig(ProtocolServerConfig config,
                                                   ProtocolTransportSpec transportSpec,
                                                   ProtocolTransportConnection transportConnection,
                                                   McpServerStatus initialStatus) {
        return new McpServerRegistration(
                config.serverId(),
                config.serverName(),
                config.protocolType().name(),
                transportSpec.transportType(),
                config.authConfig().type(),
                transportSpec.endpoint(),
                config.enabled(),
                initialStatus,
                null,
                null,
                null,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                transportSpec,
                transportConnection
        );
    }

    public McpServerRegistration withStatus(McpServerStatus nextStatus, String connectedAt, String updatedAt, String errorMessage) {
        return new McpServerRegistration(
                serverId,
                serverName,
                protocolType,
                transportType,
                authType,
                endpoint,
                enabled,
                nextStatus,
                connectedAt,
                updatedAt,
                errorMessage,
                discoveredToolCount,
                discoveredResourceCount,
                discoveredPromptCount,
                lastCapabilityRefreshAt,
                capabilityVersion,
                capabilityDigest,
                reconnectAttempt,
                maxReconnectAttempts,
                nextRetryAt,
                transportSpec,
                updateTransportConnection(mapConnectionState(nextStatus), updatedAt, connectedAt, errorMessage)
        );
    }

    public McpServerRegistration withEnabled(boolean nextEnabled, McpServerStatus nextStatus, String updatedAt, String errorMessage) {
        return new McpServerRegistration(
                serverId,
                serverName,
                protocolType,
                transportType,
                authType,
                endpoint,
                nextEnabled,
                nextStatus,
                lastConnectedAt,
                updatedAt,
                errorMessage,
                discoveredToolCount,
                discoveredResourceCount,
                discoveredPromptCount,
                lastCapabilityRefreshAt,
                capabilityVersion,
                capabilityDigest,
                reconnectAttempt,
                maxReconnectAttempts,
                nextRetryAt,
                transportSpec,
                updateTransportConnection(nextEnabled ? ProtocolTransportConnectionState.REGISTERED : ProtocolTransportConnectionState.DISABLED, updatedAt, null, errorMessage)
        );
    }

    public McpServerRegistration withCapabilityCounts(int nextToolCount,
                                                      int nextResourceCount,
                                                      int nextPromptCount,
                                                      String updatedAt,
                                                      String capabilityRefreshAt,
                                                      String nextCapabilityVersion,
                                                      String nextCapabilityDigest) {
        return new McpServerRegistration(
                serverId,
                serverName,
                protocolType,
                transportType,
                authType,
                endpoint,
                enabled,
                status,
                lastConnectedAt,
                updatedAt,
                lastError,
                nextToolCount,
                nextResourceCount,
                nextPromptCount,
                capabilityRefreshAt,
                nextCapabilityVersion,
                nextCapabilityDigest,
                reconnectAttempt,
                maxReconnectAttempts,
                nextRetryAt,
                transportSpec,
                transportConnection
        );
    }

    public McpServerRegistration withReconnectState(Integer nextReconnectAttempt,
                                                    Integer nextMaxReconnectAttempts,
                                                    String nextRetryAtValue,
                                                    String updatedAt) {
        return new McpServerRegistration(
                serverId,
                serverName,
                protocolType,
                transportType,
                authType,
                endpoint,
                enabled,
                status,
                lastConnectedAt,
                updatedAt,
                lastError,
                discoveredToolCount,
                discoveredResourceCount,
                discoveredPromptCount,
                lastCapabilityRefreshAt,
                capabilityVersion,
                capabilityDigest,
                nextReconnectAttempt,
                nextMaxReconnectAttempts,
                nextRetryAtValue,
                transportSpec,
                transportConnection
        );
    }

    public McpServerRegistration withTransportSpec(ProtocolTransportSpec nextTransportSpec, String updatedAt) {
        return new McpServerRegistration(
                serverId,
                serverName,
                protocolType,
                nextTransportSpec == null ? transportType : nextTransportSpec.transportType(),
                authType,
                nextTransportSpec == null ? endpoint : nextTransportSpec.endpoint(),
                enabled,
                status,
                lastConnectedAt,
                updatedAt,
                lastError,
                discoveredToolCount,
                discoveredResourceCount,
                discoveredPromptCount,
                lastCapabilityRefreshAt,
                capabilityVersion,
                capabilityDigest,
                reconnectAttempt,
                maxReconnectAttempts,
                nextRetryAt,
                nextTransportSpec == null ? transportSpec : nextTransportSpec,
                transportConnection
        );
    }

    public McpServerRegistration withTransportConnection(ProtocolTransportConnection nextTransportConnection) {
        return new McpServerRegistration(
                serverId,
                serverName,
                protocolType,
                transportType,
                authType,
                endpoint,
                enabled,
                status,
                lastConnectedAt,
                lastUpdatedAt,
                lastError,
                discoveredToolCount,
                discoveredResourceCount,
                discoveredPromptCount,
                lastCapabilityRefreshAt,
                capabilityVersion,
                capabilityDigest,
                reconnectAttempt,
                maxReconnectAttempts,
                nextRetryAt,
                transportSpec,
                nextTransportConnection == null ? transportConnection : nextTransportConnection
        );
    }

    private ProtocolTransportConnection updateTransportConnection(ProtocolTransportConnectionState nextState,
                                                                 String updatedAt,
                                                                 String handshakeAt,
                                                                 String errorMessage) {
        if (transportConnection == null) {
            return null;
        }
        return transportConnection.withState(nextState, updatedAt, handshakeAt, errorMessage);
    }

    private ProtocolTransportConnectionState mapConnectionState(McpServerStatus status) {
        return switch (status) {
            case REGISTERED -> ProtocolTransportConnectionState.REGISTERED;
            case CONNECTING -> ProtocolTransportConnectionState.CONNECTING;
            case RECONNECTING -> ProtocolTransportConnectionState.RECONNECTING;
            case CONNECTED -> ProtocolTransportConnectionState.CONNECTED;
            case DEGRADED -> ProtocolTransportConnectionState.DEGRADED;
            case AUTH_REQUIRED -> ProtocolTransportConnectionState.AUTH_REQUIRED;
            case FAILED -> ProtocolTransportConnectionState.FAILED;
            case CLOSED -> ProtocolTransportConnectionState.CLOSED;
            case DISABLED -> ProtocolTransportConnectionState.DISABLED;
        };
    }
}

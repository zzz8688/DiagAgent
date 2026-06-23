package io.github.zzz8688.diagagent.protocol;

import io.github.zzz8688.diagagent.mcp.McpTransportType;

public record SseProtocolServerConfig(
        String serverId,
        String serverName,
        ProtocolType protocolType,
        String endpoint,
        boolean enabled,
        ProtocolAuthConfig authConfig
) implements ProtocolServerConfig {

    @Override
    public McpTransportType transportType() {
        return McpTransportType.SSE;
    }
}

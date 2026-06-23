package io.github.zzz8688.diagagent.mcp;

import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnection;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportSpec;

public record McpServerConfigSnapshot(
        String serverId,
        String serverName,
        String protocolType,
        McpTransportType transportType,
        String endpoint,
        boolean enabled,
        String authType,
        ProtocolTransportSpec transportSpec,
        ProtocolTransportConnection transportConnection
) {
}

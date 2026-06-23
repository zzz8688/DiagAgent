package io.github.zzz8688.diagagent.mcp;

import io.github.zzz8688.diagagent.protocol.ProtocolTransportConnection;
import io.github.zzz8688.diagagent.protocol.ProtocolTransportSpec;

import java.util.List;

public record McpCapabilitySnapshot(
        String serverId,
        String serverName,
        String protocolType,
        McpServerStatus status,
        boolean enabled,
        String authType,
        String capabilityVersion,
        String capabilityDigest,
        int toolCount,
        int resourceCount,
        int promptCount,
        int userVisibleToolCount,
        int userVisibleResourceCount,
        int userVisiblePromptCount,
        List<String> toolNames,
        List<String> resourceUris,
        List<String> promptNames,
        List<McpToolRegistration> toolRegistrations,
        List<McpResourceRegistration> resourceRegistrations,
        List<McpPromptRegistration> promptRegistrations,
        ProtocolTransportSpec transportSpec,
        ProtocolTransportConnection transportConnection,
        String lastRefreshedAt
) {
}

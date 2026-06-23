package io.github.zzz8688.diagagent.mcp;

import java.util.Map;

public record McpSerializedClient(
        String serverId,
        String name,
        String type,
        String originalStatus,
        String error,
        Integer reconnectAttempt,
        Integer maxReconnectAttempts,
        Map<String, Object> capabilities,
        McpServerConfigSnapshot config,
        Map<String, Object> runtime
) {
}

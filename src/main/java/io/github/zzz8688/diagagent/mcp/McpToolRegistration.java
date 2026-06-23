package io.github.zzz8688.diagagent.mcp;

import java.util.Map;

public record McpToolRegistration(
        String toolName,
        String description,
        String serverId,
        boolean enabled,
        boolean userVisible,
        Object inputSchema,
        Object outputSchema,
        Object annotations,
        Map<String, Object> metadata
) {

    public McpToolRegistration(String toolName, String description, String serverId, boolean enabled, boolean userVisible) {
        this(toolName, description, serverId, enabled, userVisible, null, null, null, Map.of());
    }
}

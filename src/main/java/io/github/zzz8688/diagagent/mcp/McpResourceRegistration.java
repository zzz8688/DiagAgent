package io.github.zzz8688.diagagent.mcp;

import java.util.Map;

public record McpResourceRegistration(
        String uri,
        String name,
        String description,
        String mimeType,
        String serverId,
        boolean userVisible,
        Map<String, Object> metadata
) {

    public McpResourceRegistration(String uri, String name, String description, String mimeType, String serverId, boolean userVisible) {
        this(uri, name, description, mimeType, serverId, userVisible, Map.of());
    }
}

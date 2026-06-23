package io.github.zzz8688.diagagent.mcp;

import java.util.List;
import java.util.Map;

public record McpPromptRegistration(
        String promptName,
        String description,
        String serverId,
        boolean userVisible,
        List<Map<String, Object>> arguments,
        Map<String, Object> metadata
) {

    public McpPromptRegistration(String promptName, String description, String serverId, boolean userVisible) {
        this(promptName, description, serverId, userVisible, List.of(), Map.of());
    }
}

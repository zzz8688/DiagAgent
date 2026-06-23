package io.github.zzz8688.diagagent.mcp;

public record McpSerializedTool(
        String name,
        String description,
        Object inputJsonSchema,
        boolean isMcp,
        String originalToolName,
        String serverId,
        boolean enabled,
        boolean userVisible
) {
}

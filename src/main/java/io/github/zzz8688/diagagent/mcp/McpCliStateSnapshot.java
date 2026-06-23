package io.github.zzz8688.diagagent.mcp;

import java.util.List;
import java.util.Map;

public record McpCliStateSnapshot(
        List<McpSerializedClient> clients,
        Map<String, McpServerConfigSnapshot> configs,
        List<McpSerializedTool> tools,
        Map<String, List<McpResourceRegistration>> resources,
        Map<String, String> normalizedNames
) {
}

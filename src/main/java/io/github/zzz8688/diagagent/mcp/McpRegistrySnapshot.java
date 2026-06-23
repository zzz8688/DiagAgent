package io.github.zzz8688.diagagent.mcp;

import java.util.List;

public record McpRegistrySnapshot(
        List<McpServerRegistration> servers,
        List<McpToolRegistration> tools,
        List<McpResourceRegistration> resources,
        List<McpPromptRegistration> prompts,
        List<McpCapabilitySnapshot> capabilitySnapshots
) {
}

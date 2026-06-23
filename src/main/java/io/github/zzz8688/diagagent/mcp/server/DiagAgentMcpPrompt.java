package io.github.zzz8688.diagagent.mcp.server;

import java.util.List;
import java.util.Map;

public record DiagAgentMcpPrompt(
        String name,
        String description,
        List<Map<String, Object>> arguments,
        Object _meta
) {
}

package io.github.zzz8688.diagagent.mcp.server;

public record DiagAgentMcpResource(
        String uri,
        String name,
        String description,
        String mimeType,
        Object _meta
) {
}

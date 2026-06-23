package io.github.zzz8688.diagagent.mcp.server;

public record DiagAgentMcpExportedTool(
        String name,
        String description,
        Object inputSchema,
        Object outputSchema,
        Object annotations,
        Object _meta
) {
}

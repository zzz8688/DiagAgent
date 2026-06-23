package io.github.zzz8688.diagagent.mcp.server;

public record DiagAgentMcpToolCallResult(
        boolean error,
        String text,
        java.util.Map<String, Object> structuredContent
) {

    public static DiagAgentMcpToolCallResult success(String text, java.util.Map<String, Object> structuredContent) {
        return new DiagAgentMcpToolCallResult(false, text, structuredContent);
    }

    public static DiagAgentMcpToolCallResult error(String text) {
        return new DiagAgentMcpToolCallResult(true, text, null);
    }
}

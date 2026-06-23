package io.github.zzz8688.diagagent.mcp.server;

public record DiagAgentMcpResourceReadResult(
        boolean error,
        String message,
        String uri,
        String mimeType,
        String text
) {

    public static DiagAgentMcpResourceReadResult success(String uri, String mimeType, String text) {
        return new DiagAgentMcpResourceReadResult(false, null, uri, mimeType, text);
    }

    public static DiagAgentMcpResourceReadResult error(String message) {
        return new DiagAgentMcpResourceReadResult(true, message, null, null, null);
    }
}

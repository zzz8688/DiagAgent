package io.github.zzz8688.diagagent.mcp.server;

public record DiagAgentMcpPromptGetResult(
        boolean error,
        String message,
        String description,
        String text
) {

    public static DiagAgentMcpPromptGetResult success(String description, String text) {
        return new DiagAgentMcpPromptGetResult(false, null, description, text);
    }

    public static DiagAgentMcpPromptGetResult error(String message) {
        return new DiagAgentMcpPromptGetResult(true, message, null, null);
    }
}

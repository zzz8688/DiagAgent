package io.github.zzz8688.diagagent.tools.registry;

public record ToolBackendStrategy(
        String logicalToolName,
        String strategyType,
        String preferredBackend,
        String fallbackBackend,
        String notes
) {

    public ToolBackendStrategy {
        logicalToolName = safe(logicalToolName);
        strategyType = safe(strategyType);
        preferredBackend = safe(preferredBackend);
        fallbackBackend = safe(fallbackBackend);
        notes = safe(notes);
    }

    public static ToolBackendStrategy localOnly(String logicalToolName) {
        return new ToolBackendStrategy(
                logicalToolName,
                "local-only",
                "local",
                "",
                "Logical tool is executed in-process without a remote protocol backend."
        );
    }

    public static ToolBackendStrategy mcpOnly(String logicalToolName, String providerId) {
        return new ToolBackendStrategy(
                logicalToolName,
                "mcp-only",
                "mcp:" + safe(providerId),
                "",
                "Logical tool is backed only by a remote MCP server."
        );
    }

    public static ToolBackendStrategy mcpPreferredWithLocalFallback(String logicalToolName,
                                                                    String providerId,
                                                                    String fallbackBackend) {
        return new ToolBackendStrategy(
                logicalToolName,
                "mcp-preferred-with-local-fallback",
                "mcp:" + safe(providerId),
                safe(fallbackBackend),
                "Logical tool stays stable while runtime prefers MCP and falls back to a local implementation when the remote backend is unavailable."
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.mcp.McpClientAdapter;
import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxProtectedActionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxTargetType;
import io.github.zzz8688.diagagent.service.ReactLoopGuardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class McpToolActionAdapter implements ReactActionAdapter {

    private final McpRegistryService mcpRegistryService;
    private final List<McpClientAdapter> mcpClientAdapters;
    private final SandboxExecutionService sandboxExecutionService;
    private final ReactLoopGuardService reactLoopGuardService;

    @Override
    public ReactActionType actionType() {
        return ReactActionType.CALL_MCP_TOOL;
    }

    @Override
    public ReactActionExecutionResult execute(ReactAction action,
                                              String userQuestion,
                                              String sessionId,
                                              boolean readOnly) {
        String target = action.target();
        if (target == null || !target.contains("/")) {
            return ReactActionExecutionResult.failed(action, null, "MCP tool target must use serverId/toolName format");
        }
        String[] parts = target.split("/", 2);
        String serverId = parts[0];
        String toolName = parts[1];
        if (mcpRegistryService.findTool(serverId, toolName).isEmpty()) {
            return ReactActionExecutionResult.failed(action, null, "Unknown MCP tool target: " + target);
        }
        McpClientAdapter clientAdapter = mcpClientAdapters.stream()
                .filter(adapter -> adapter.serverId().equalsIgnoreCase(serverId))
                .findFirst()
                .orElse(null);
        if (clientAdapter == null) {
            return ReactActionExecutionResult.unsupported(action, "No MCP client adapter found for serverId: " + serverId);
        }
        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            if (action.arguments() != null) {
                arguments.putAll(action.arguments());
            }
            String targetName = serverId + "/" + toolName;
            reactLoopGuardService.onToolCall(sessionId, targetName, summarizeArguments(arguments));
            SandboxProtectedActionEnvelope<Object> envelope = sandboxExecutionService.executeProtectedAction(
                    targetName,
                    SandboxTargetType.MCP_TOOL,
                    targetName,
                    Map.copyOf(arguments),
                    null,
                    () -> clientAdapter.invokeTool(toolName, arguments)
            );
            if (!envelope.success()) {
                return ReactActionExecutionResult.failed(
                        action,
                        null,
                        "mcp tool failed: " + defaultText(envelope.errorMessage(), "sandbox protected action failed")
                );
            }
            Object payload = envelope.result();
            return ReactActionExecutionResult.completed(action, payload, "mcp tool completed");
        } catch (Exception e) {
            return ReactActionExecutionResult.failed(action, null, "mcp tool failed: " + e.getMessage());
        }
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String summarizeArguments(Map<String, Object> arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return "";
        }
        String summary = arguments.toString().replaceAll("\\s+", " ").trim();
        if (summary.length() <= 200) {
            return summary;
        }
        return summary.substring(0, 200);
    }
}

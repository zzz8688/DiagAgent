package io.github.zzz8688.diagagent.tools.registry;

import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import io.github.zzz8688.diagagent.mcp.McpRegistrySnapshot;
import io.github.zzz8688.diagagent.mcp.McpServerRegistration;
import io.github.zzz8688.diagagent.mcp.McpToolRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class DefaultToolRegistry implements ToolRegistry {

    private final LocalToolCatalog localToolCatalog;
    private final McpRegistryService mcpRegistryService;

    @Override
    public List<RegisteredTool> listTools() {
        List<RegisteredTool> merged = new ArrayList<>(localToolCatalog.listLocalTools());
        merged.addAll(listMcpTools());
        merged.sort(Comparator.comparing(RegisteredTool::name));
        return List.copyOf(merged);
    }

    @Override
    public List<RegisteredTool> listVisibleTools() {
        return listTools().stream()
                .filter(RegisteredTool::userVisible)
                .filter(RegisteredTool::enabled)
                .toList();
    }

    @Override
    public RegisteredTool findTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return null;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return listTools().stream()
                .filter(tool -> tool.name().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private List<RegisteredTool> listMcpTools() {
        McpRegistrySnapshot snapshot = mcpRegistryService.snapshot();
        return snapshot.tools().stream()
                .map(tool -> toRegisteredTool(tool, snapshot.servers()))
                .toList();
    }

    private RegisteredTool toRegisteredTool(McpToolRegistration tool, List<McpServerRegistration> servers) {
        McpServerRegistration server = servers.stream()
                .filter(candidate -> candidate.serverId().equalsIgnoreCase(tool.serverId()))
                .findFirst()
                .orElse(null);
        String transport = server == null ? "mcp" : server.transportType().name().toLowerCase(Locale.ROOT);
        boolean enabled = tool.enabled() && server != null && server.enabled();
        return new RegisteredTool(
                tool.toolName(),
                tool.description(),
                ToolSourceType.MCP,
                "mcp",
                tool.serverId(),
                null,
                ToolBackendStrategy.mcpOnly(tool.toolName(), tool.serverId()),
                enabled,
                tool.userVisible(),
                transport,
                "native-mcp"
        );
    }
}

package io.github.zzz8688.diagagent.protocol;

import io.github.zzz8688.diagagent.mcp.McpRegistryService;
import io.github.zzz8688.diagagent.mcp.McpPromptRegistration;
import io.github.zzz8688.diagagent.mcp.McpResourceRegistration;
import io.github.zzz8688.diagagent.mcp.McpServerRegistration;
import io.github.zzz8688.diagagent.mcp.McpServerStatus;
import io.github.zzz8688.diagagent.mcp.McpToolRegistration;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProtocolLifecycleManager {

    private final McpRegistryService mcpRegistryService;
    private final ProtocolTransportFactory protocolTransportFactory;

    public void register(ManagedProtocolClient client) {
        ProtocolServerConfig config = client.serverConfig();
        if (config.protocolType() != ProtocolType.MCP) {
            return;
        }
        mcpRegistryService.registerServer(toServerRegistration(config));
        mcpRegistryService.replaceServerTools(config.serverId(), toToolRegistrations(config.serverId(), client.bootstrapTools()));
        mcpRegistryService.replaceServerResources(config.serverId(), toResourceRegistrations(config.serverId(), client.bootstrapResources()));
        mcpRegistryService.replaceServerPrompts(config.serverId(), toPromptRegistrations(config.serverId(), client.bootstrapPrompts()));
    }

    public void markDisabled(ManagedProtocolClient client) {
        mcpRegistryService.disableServer(client.serverId());
    }

    public void markConnecting(ManagedProtocolClient client) {
        mcpRegistryService.markConnecting(client.serverId());
    }

    public void markConnected(ManagedProtocolClient client, List<ProtocolToolDescriptor> toolDescriptors) {
        markConnected(client, toolDescriptors, client.bootstrapResources(), client.bootstrapPrompts());
    }

    public void markConnected(ManagedProtocolClient client,
                              List<ProtocolToolDescriptor> toolDescriptors,
                              List<ProtocolResourceDescriptor> resourceDescriptors,
                              List<ProtocolPromptDescriptor> promptDescriptors) {
        if (toolDescriptors != null) {
            mcpRegistryService.replaceServerTools(client.serverId(), toToolRegistrations(client.serverId(), toolDescriptors));
        }
        if (resourceDescriptors != null) {
            mcpRegistryService.replaceServerResources(client.serverId(), toResourceRegistrations(client.serverId(), resourceDescriptors));
        }
        if (promptDescriptors != null) {
            mcpRegistryService.replaceServerPrompts(client.serverId(), toPromptRegistrations(client.serverId(), promptDescriptors));
        }
        mcpRegistryService.markConnected(client.serverId());
    }

    public void markDegraded(ManagedProtocolClient client, String message) {
        mcpRegistryService.markDegraded(client.serverId(), message);
    }

    public void markAuthRequired(ManagedProtocolClient client, String message) {
        mcpRegistryService.markAuthRequired(client.serverId(), message);
    }

    public void markFailed(ManagedProtocolClient client, String message) {
        mcpRegistryService.markFailed(client.serverId(), message);
    }

    public void markClosed(ManagedProtocolClient client) {
        mcpRegistryService.markClosed(client.serverId());
    }

    private McpServerRegistration toServerRegistration(ProtocolServerConfig config) {
        ProtocolTransportSpec transportSpec = protocolTransportFactory.create(config);
        ProtocolTransportConnection transportConnection = protocolTransportFactory.createConnection(config);
        McpServerStatus initialStatus = config.enabled() ? McpServerStatus.REGISTERED : McpServerStatus.DISABLED;
        return McpServerRegistration.fromConfig(config, transportSpec, transportConnection, initialStatus);
    }

    private List<McpToolRegistration> toToolRegistrations(String serverId, List<ProtocolToolDescriptor> tools) {
        return tools.stream()
                .map(tool -> new McpToolRegistration(
                        tool.name(),
                        tool.description(),
                        serverId,
                        tool.enabled(),
                        tool.userVisible(),
                        tool.inputSchema(),
                        tool.outputSchema(),
                        tool.annotations(),
                        tool.metadata()
                ))
                .toList();
    }

    private List<McpResourceRegistration> toResourceRegistrations(String serverId, List<ProtocolResourceDescriptor> resources) {
        return resources.stream()
                .map(resource -> new McpResourceRegistration(
                        resource.uri(),
                        resource.name(),
                        resource.description(),
                        resource.mimeType(),
                        serverId,
                        resource.userVisible(),
                        resource.metadata()
                ))
                .toList();
    }

    private List<McpPromptRegistration> toPromptRegistrations(String serverId, List<ProtocolPromptDescriptor> prompts) {
        return prompts.stream()
                .map(prompt -> new McpPromptRegistration(
                        prompt.name(),
                        prompt.description(),
                        serverId,
                        prompt.userVisible(),
                        prompt.arguments(),
                        prompt.metadata()
                ))
                .toList();
    }
}

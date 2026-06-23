package io.github.zzz8688.diagagent.mcp;

import io.github.zzz8688.diagagent.protocol.ManagedProtocolClient;
import io.github.zzz8688.diagagent.protocol.ProtocolLifecycleManager;
import io.github.zzz8688.diagagent.protocol.ProtocolPromptDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolResourceDescriptor;
import io.github.zzz8688.diagagent.protocol.ProtocolToolDescriptor;

import java.util.List;
import java.util.Map;

public interface McpClientAdapter extends ManagedProtocolClient {

    List<ProtocolToolDescriptor> discoverTools() throws Exception;

    default List<ProtocolResourceDescriptor> discoverResources() throws Exception {
        return bootstrapResources();
    }

    default List<ProtocolPromptDescriptor> discoverPrompts() throws Exception {
        return bootstrapPrompts();
    }

    Object invokeTool(String toolName, Map<String, Object> arguments) throws Exception;

    default void refreshCapabilities(ProtocolLifecycleManager protocolLifecycleManager) {
        protocolLifecycleManager.register(this);
        if (!serverConfig().enabled()) {
            protocolLifecycleManager.markDisabled(this);
            return;
        }
        try {
            protocolLifecycleManager.markConnecting(this);
            List<ProtocolToolDescriptor> tools = discoverTools();
            List<ProtocolResourceDescriptor> resources = discoverResources();
            List<ProtocolPromptDescriptor> prompts = discoverPrompts();
            if (tools.isEmpty() && resources.isEmpty() && prompts.isEmpty()) {
                protocolLifecycleManager.markDegraded(this, serverId() + " 未发现任何 MCP capabilities");
                return;
            }
            protocolLifecycleManager.markConnected(this, tools, resources, prompts);
        } catch (Exception e) {
            protocolLifecycleManager.markFailed(this, e.getMessage());
            throw new IllegalStateException("刷新 MCP capability 失败: serverId=" + serverId(), e);
        }
    }
}

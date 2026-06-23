package io.github.zzz8688.diagagent.protocol;

import java.util.List;

public interface ManagedProtocolClient {

    ProtocolServerConfig serverConfig();

    default String serverId() {
        return serverConfig().serverId();
    }

    default List<ProtocolToolDescriptor> bootstrapTools() {
        return List.of();
    }

    default List<ProtocolResourceDescriptor> bootstrapResources() {
        return List.of();
    }

    default List<ProtocolPromptDescriptor> bootstrapPrompts() {
        return List.of();
    }
}

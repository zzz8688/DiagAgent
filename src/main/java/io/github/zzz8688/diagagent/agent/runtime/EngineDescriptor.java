package io.github.zzz8688.diagagent.agent.runtime;

public record EngineDescriptor(
        String adapterId,
        String displayName,
        EngineProviderType providerType,
        String defaultModelName,
        boolean streamingSupported,
        boolean available,
        String notes
) {
}

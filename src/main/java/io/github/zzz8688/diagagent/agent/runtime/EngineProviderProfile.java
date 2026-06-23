package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Map;

public record EngineProviderProfile(
        EngineProviderType providerType,
        String modelName,
        String sdkBoundary,
        Map<String, String> metadata
) {

    public EngineProviderProfile {
        providerType = providerType == null ? EngineProviderType.UNKNOWN : providerType;
        modelName = modelName == null ? "" : modelName;
        sdkBoundary = sdkBoundary == null ? "" : sdkBoundary;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

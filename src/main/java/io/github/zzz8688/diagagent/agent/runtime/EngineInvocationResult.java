package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Map;

public record EngineInvocationResult(
        String rawText,
        String finishReason,
        EngineProviderProfile providerProfile,
        Map<String, String> providerMetadata
) {

    public EngineInvocationResult {
        rawText = rawText == null ? "" : rawText;
        finishReason = finishReason == null ? "completed" : finishReason;
        providerProfile = providerProfile == null
                ? new EngineProviderProfile(EngineProviderType.UNKNOWN, "", "", Map.of())
                : providerProfile;
        providerMetadata = providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata);
    }
}

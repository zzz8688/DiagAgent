package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Map;

public record EngineResponse(
        boolean success,
        String adapterId,
        EngineProviderType providerType,
        String modelName,
        String rawText,
        String finishReason,
        Map<String, String> providerMetadata,
        String errorCode,
        String errorMessage
) {

    public static EngineResponse success(String adapterId,
                                         EngineProviderType providerType,
                                         String modelName,
                                         String rawText,
                                         String finishReason,
                                         Map<String, String> providerMetadata) {
        return new EngineResponse(
                true,
                adapterId,
                providerType == null ? EngineProviderType.UNKNOWN : providerType,
                modelName == null ? "" : modelName,
                rawText == null ? "" : rawText,
                finishReason == null ? "completed" : finishReason,
                providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata),
                "",
                ""
        );
    }

    public static EngineResponse failure(String adapterId,
                                         EngineProviderType providerType,
                                         String modelName,
                                         String errorCode,
                                         String errorMessage,
                                         Map<String, String> providerMetadata) {
        return new EngineResponse(
                false,
                adapterId,
                providerType == null ? EngineProviderType.UNKNOWN : providerType,
                modelName == null ? "" : modelName,
                "",
                "failed",
                providerMetadata == null ? Map.of() : Map.copyOf(providerMetadata),
                errorCode == null ? "engine_error" : errorCode,
                errorMessage == null ? "Engine invocation failed" : errorMessage
        );
    }
}

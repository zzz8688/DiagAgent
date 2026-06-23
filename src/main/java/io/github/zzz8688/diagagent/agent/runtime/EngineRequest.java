package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Map;
import java.util.UUID;

public record EngineRequest(
        String taskId,
        String sessionId,
        String engineType,
        String operationName,
        MainAgentPromptAssembler.PromptBundle promptBundle,
        PromptEnvelope promptEnvelope,
        boolean readOnly,
        Map<String, String> metadata
) {

    public static EngineRequest forPrompt(String sessionId,
                                          String engineType,
                                          String operationName,
                                          MainAgentPromptAssembler.PromptBundle promptBundle,
                                          boolean readOnly,
                                          Map<String, String> metadata) {
        return new EngineRequest(
                UUID.randomUUID().toString(),
                sessionId,
                engineType,
                operationName,
                promptBundle,
                promptBundle == null ? null : promptBundle.toPromptEnvelope(),
                readOnly,
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }

    public static EngineRequest forEnvelope(String sessionId,
                                            String engineType,
                                            String operationName,
                                            PromptEnvelope promptEnvelope,
                                            boolean readOnly,
                                            Map<String, String> metadata) {
        return new EngineRequest(
                UUID.randomUUID().toString(),
                sessionId,
                engineType,
                operationName,
                null,
                promptEnvelope,
                readOnly,
                metadata == null ? Map.of() : Map.copyOf(metadata)
        );
    }
}

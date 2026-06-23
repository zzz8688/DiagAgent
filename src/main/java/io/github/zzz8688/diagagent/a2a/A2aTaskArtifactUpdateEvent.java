package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTaskArtifactUpdateEvent(
        String taskId,
        A2aArtifact artifact,
        String contextId,
        Boolean append,
        Boolean lastChunk,
        Map<String, Object> metadata
) implements A2aPayload {

    public A2aTaskArtifactUpdateEvent {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (artifact == null) {
            throw new IllegalArgumentException("artifact must not be null");
        }
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("contextId must not be blank");
        }
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
    }

    @Override
    public String payloadType() {
        return "artifactUpdate";
    }

    @Override
    public String taskId() {
        return taskId;
    }

    @Override
    public String contextId() {
        return contextId;
    }
}

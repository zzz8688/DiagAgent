package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTask(
        String id,
        String contextId,
        A2aTaskStatus status,
        List<A2aArtifact> artifacts,
        List<A2aMessage> history,
        Map<String, Object> metadata
) implements A2aPayload {

    public A2aTask {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("contextId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        history = history == null ? List.of() : List.copyOf(history);
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
    }

    @Override
    public String payloadType() {
        return "task";
    }

    @Override
    public String taskId() {
        return id;
    }

    @Override
    public String contextId() {
        return contextId;
    }

    @Override
    public String taskState() {
        return status.state().name();
    }
}

package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTaskStatusUpdateEvent(
        String taskId,
        A2aTaskStatus status,
        String contextId,
        Map<String, Object> metadata
) implements A2aPayload {

    public A2aTaskStatusUpdateEvent {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("contextId must not be blank");
        }
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
    }

    @Override
    public String payloadType() {
        return "statusUpdate";
    }

    @Override
    public String taskId() {
        return taskId;
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

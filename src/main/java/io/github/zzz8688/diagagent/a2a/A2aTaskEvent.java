package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aTaskEvent(
        long sequence,
        String agentId,
        String sourceMethod,
        A2aPayload event,
        String receivedAt
) {

    public A2aTaskEvent {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
    }

    @JsonProperty("eventType")
    public String eventType() {
        return event.payloadType();
    }

    @JsonProperty("taskId")
    public String taskId() {
        return event.taskId();
    }

    @JsonProperty("contextId")
    public String contextId() {
        return event.contextId();
    }

    @JsonProperty("taskState")
    public String taskState() {
        return event.taskState();
    }
}

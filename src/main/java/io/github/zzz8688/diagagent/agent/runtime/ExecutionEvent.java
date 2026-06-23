package io.github.zzz8688.diagagent.agent.runtime;

import java.time.Instant;
import java.util.Map;

public record ExecutionEvent(
        long cursor,
        String eventId,
        ExecutionEventType eventType,
        String taskId,
        String sessionId,
        String source,
        Map<String, String> payload,
        Instant createdAt
) {
    public ExecutionEvent {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}

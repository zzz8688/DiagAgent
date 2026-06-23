package io.github.zzz8688.diagagent.mq;

import java.time.Instant;
import java.util.Map;

public record KafkaLogMessage(
        String messageId,
        Instant timestamp,
        String sourceType,
        String sourceId,
        String host,
        String level,
        String sessionId,
        String taskId,
        String traceId,
        String summary,
        String content,
        Map<String, String> attributes
) {
    public KafkaLogMessage {
        timestamp = timestamp == null ? Instant.now() : timestamp;
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}

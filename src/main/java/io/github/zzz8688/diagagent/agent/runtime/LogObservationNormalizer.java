package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.parser.LogEntry;
import io.github.zzz8688.diagagent.parser.LogParserService;
import io.github.zzz8688.diagagent.mq.KafkaLogMessage;
import io.github.zzz8688.diagagent.store.LogVectorStoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.LongAdder;

@Slf4j
@Component
@RequiredArgsConstructor
public class LogObservationNormalizer {

    private final ExecutionEventBus executionEventBus;
    private final TaskManager taskManager;
    private final LogVectorStoreService logVectorStoreService;
    private final LogParserService logParserService;
    private final LongAdder filteredCount = new LongAdder();
    private final LongAdder indexedCount = new LongAdder();

    public LogIngestOutcome accept(KafkaLogMessage message) {
        if (message == null) {
            return LogIngestOutcome.ignored(filteredCount.sum(), indexedCount.sum());
        }
        NormalizedLogObservation normalized = normalize(message);
        if (!shouldIndex(normalized)) {
            filteredCount.increment();
            return LogIngestOutcome.filtered(normalized.messageId(), normalized.level(), normalized.service(), filteredCount.sum(), indexedCount.sum());
        }
        logVectorStoreService.ingest(normalized.toLogDocument());
        publishIngested(normalized);
        publishObservation(normalized);
        indexedCount.increment();
        return LogIngestOutcome.indexed(normalized.messageId(), normalized.level(), normalized.service(), filteredCount.sum(), indexedCount.sum());
    }

    private NormalizedLogObservation normalize(KafkaLogMessage message) {
        String resolvedSessionId = safe(message.sessionId());
        String resolvedTaskId = resolveTaskId(message.taskId(), resolvedSessionId);
        Instant createdAt = message.timestamp() == null ? Instant.now() : message.timestamp();
        String messageId = safe(message.messageId()).isBlank() ? UUID.randomUUID().toString() : safe(message.messageId());
        LogEntry parsed = logParserService.parse(safe(message.content()));
        String resolvedService = firstNonBlank(message.sourceId(), parsed.getService(), "unknown-service");
        String level = normalizeLevel(firstNonBlank(message.level(), parsed.getLevel()));
        String resolvedSourceType = safe(message.sourceType());
        String template = extractTemplate(message.content());
        Map<String, String> attributes = new LinkedHashMap<>(message.attributes());
        attributes.putIfAbsent("service", resolvedService);
        attributes.put("template", template);
        return new NormalizedLogObservation(
                messageId,
                resolvedSourceType,
                resolvedService,
                safe(message.host()),
                level,
                resolvedSessionId,
                resolvedTaskId,
                safe(message.traceId()),
                buildSummary(resolvedSourceType, resolvedService, level, message),
                safe(message.content()),
                template,
                Map.copyOf(attributes),
                createdAt
        );
    }

    private void publishIngested(NormalizedLogObservation normalized) {
        executionEventBus.publish(
                ExecutionEventType.LOG_INGESTED,
                normalized.taskId(),
                normalized.sessionId(),
                "kafka-log-consumer",
                basePayload(normalized)
        );
    }

    private void publishObservation(NormalizedLogObservation normalized) {
        Map<String, String> payload = new LinkedHashMap<>(basePayload(normalized));
        payload.put("status", "RECEIVED");
        payload.put("message", normalized.summary());
        payload.put("raw", normalized.content());
        executionEventBus.publish(
                ExecutionEventType.LOG_OBSERVATION_RECEIVED,
                normalized.taskId(),
                normalized.sessionId(),
                "log-observation-normalizer",
                Map.copyOf(payload)
        );
    }

    private Map<String, String> basePayload(NormalizedLogObservation normalized) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("actionId", "log-" + normalized.messageId());
        payload.put("actionType", "LOG_STREAM");
        payload.put("target", normalized.sourceType() + "/" + normalized.service());
        payload.put("sourceType", normalized.sourceType());
        payload.put("sourceId", normalized.service());
        payload.put("host", normalized.host());
        payload.put("level", normalized.level());
        payload.put("summary", normalized.summary());
        payload.put("traceId", normalized.traceId());
        payload.put("template", normalized.template());
        payload.put("indexed", "true");
        payload.put("turnIndex", "0");
        return payload;
    }

    private String resolveTaskId(String taskId, String sessionId) {
        if (taskId != null && !taskId.isBlank()) {
            return taskId.trim();
        }
        if (sessionId == null || sessionId.isBlank()) {
            return "";
        }
        Optional<RuntimeTask> runtimeTask = taskManager.findLatestBySessionId(sessionId);
        return runtimeTask.map(RuntimeTask::taskId).orElse("");
    }

    private String normalizeLevel(String level) {
        String normalized = safe(level).toUpperCase();
        return normalized.isBlank() ? "INFO" : normalized;
    }

    private boolean shouldIndex(NormalizedLogObservation normalized) {
        if (normalized == null) {
            return false;
        }
        return "WARN".equals(normalized.level()) || "ERROR".equals(normalized.level());
    }

    private String buildSummary(String sourceType, String service, String level, KafkaLogMessage message) {
        String source = safe(sourceType) + "/" + safe(service);
        String summary = safe(message.summary());
        String traceId = safe(message.traceId());
        String requestId = safe(message.attributes().get("requestId"));
        String upstreamDependency = safe(message.attributes().get("upstreamDependency"));
        if (!summary.isBlank()) {
            return appendCorrelationHints("[" + level + "] " + source + " " + summary, traceId, requestId, upstreamDependency);
        }
        String raw = safe(message.content());
        if (raw.length() > 180) {
            raw = raw.substring(0, 180) + "...";
        }
        return appendCorrelationHints("[" + level + "] " + source + " " + raw, traceId, requestId, upstreamDependency);
    }

    private String appendCorrelationHints(String base,
                                          String traceId,
                                          String requestId,
                                          String upstreamDependency) {
        StringBuilder builder = new StringBuilder(safe(base));
        if (!traceId.isBlank()) {
            builder.append(" traceId=").append(traceId);
        }
        if (!requestId.isBlank()) {
            builder.append(" requestId=").append(requestId);
        }
        if (!upstreamDependency.isBlank()) {
            builder.append(" upstream=").append(upstreamDependency);
        }
        return builder.toString().trim();
    }

    private String extractTemplate(String raw) {
        return safe(raw)
                .replaceAll("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}", "<UUID>")
                .replaceAll("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b", "<IP>")
                .replaceAll("\\b\\d+\\b", "<NUM>");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private record NormalizedLogObservation(
            String messageId,
            String sourceType,
            String service,
            String host,
            String level,
            String sessionId,
            String taskId,
            String traceId,
            String summary,
            String content,
            String template,
            Map<String, String> attributes,
            Instant createdAt
    ) {
        private LogVectorStoreService.LogDocument toLogDocument() {
            return new LogVectorStoreService.LogDocument(
                    messageId,
                    createdAt,
                    level,
                    service,
                    sourceType,
                    service,
                    host,
                    traceId,
                    taskId,
                    sessionId,
                    summary,
                    content,
                    template,
                    attributes
            );
        }
    }

    public record LogIngestOutcome(
            boolean indexed,
            boolean filtered,
            String messageId,
            String level,
            String service,
            long filteredCount,
            long indexedCount
    ) {
        private static LogIngestOutcome indexed(String messageId,
                                                String level,
                                                String service,
                                                long filteredCount,
                                                long indexedCount) {
            return new LogIngestOutcome(true, false, messageId, level, service, filteredCount, indexedCount);
        }

        private static LogIngestOutcome filtered(String messageId,
                                                 String level,
                                                 String service,
                                                 long filteredCount,
                                                 long indexedCount) {
            return new LogIngestOutcome(false, true, messageId, level, service, filteredCount, indexedCount);
        }

        private static LogIngestOutcome ignored(long filteredCount, long indexedCount) {
            return new LogIngestOutcome(false, false, "", "", "", filteredCount, indexedCount);
        }
    }
}

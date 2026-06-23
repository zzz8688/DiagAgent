package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MemoryCompressionEventPublisher {

    private final TaskManager taskManager;
    private final ExecutionEventBus executionEventBus;

    public void publishSummaryRefreshed(String sessionId,
                                        String memoryId,
                                        String topic,
                                        String summaryType,
                                        int baseVersion,
                                        int nextVersion,
                                        int deltaMessageCount,
                                        int deltaUserQuestionCount,
                                        int summarizedUserQuestionCount,
                                        int lastSummarizedMessageCount) {
        publish(
                ExecutionEventType.MEMORY_SUMMARY_REFRESHED,
                sessionId,
                Map.of(
                        "memoryId", safe(memoryId),
                        "topic", safe(topic),
                        "summaryType", safe(summaryType),
                        "baseVersion", Integer.toString(baseVersion),
                        "nextVersion", Integer.toString(nextVersion),
                        "deltaMessageCount", Integer.toString(Math.max(0, deltaMessageCount)),
                        "deltaUserQuestionCount", Integer.toString(Math.max(0, deltaUserQuestionCount)),
                        "summarizedUserQuestionCount", Integer.toString(Math.max(0, summarizedUserQuestionCount)),
                        "lastSummarizedMessageCount", Integer.toString(Math.max(0, lastSummarizedMessageCount))
                )
        );
    }

    public void publishContextCompacted(String sessionId,
                                        String memoryId,
                                        String compactionLevel,
                                        boolean minimalContextMode,
                                        int originalMessageCount,
                                        int retainedMessageCount,
                                        int usedTokens,
                                        int maxBudgetTokens,
                                        boolean summaryPresent) {
        publish(
                ExecutionEventType.MEMORY_CONTEXT_COMPACTED,
                sessionId,
                Map.of(
                        "memoryId", safe(memoryId),
                        "compactionLevel", safe(compactionLevel),
                        "minimalContextMode", Boolean.toString(minimalContextMode),
                        "originalMessageCount", Integer.toString(Math.max(0, originalMessageCount)),
                        "retainedMessageCount", Integer.toString(Math.max(0, retainedMessageCount)),
                        "droppedMessageCount", Integer.toString(Math.max(0, originalMessageCount - retainedMessageCount)),
                        "usedBudgetTokens", Integer.toString(Math.max(0, usedTokens)),
                        "maxBudgetTokens", Integer.toString(Math.max(1, maxBudgetTokens)),
                        "summaryPresent", Boolean.toString(summaryPresent)
                )
        );
    }

    private void publish(ExecutionEventType eventType, String sessionId, Map<String, String> payload) {
        String normalizedSessionId = normalize(sessionId);
        String taskId = taskManager.findLatestBySessionId(normalizedSessionId)
                .map(RuntimeTask::taskId)
                .orElse("");
        Map<String, String> eventPayload = new LinkedHashMap<>();
        eventPayload.put("sessionId", safe(normalizedSessionId));
        eventPayload.put("taskId", safe(taskId));
        if (payload != null && !payload.isEmpty()) {
            payload.forEach((key, value) -> eventPayload.put(safe(key), safe(value)));
        }
        executionEventBus.publish(
                eventType,
                taskId,
                normalizedSessionId,
                "summary-chat-memory",
                Map.copyOf(eventPayload)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

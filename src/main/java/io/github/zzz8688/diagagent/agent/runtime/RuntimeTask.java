package io.github.zzz8688.diagagent.agent.runtime;

import java.time.Instant;

public record RuntimeTask(
        String taskId,
        String sessionId,
        RuntimeTaskStatus status,
        String description,
        String requestMode,
        String currentNode,
        String engine,
        String lastError,
        String outputSummary,
        Instant createdAt,
        Instant updatedAt,
        Instant finishedAt
) {
    public RuntimeTask withLifecycle(RuntimeTaskStatus nextStatus,
                                     String nextCurrentNode,
                                     String nextLastError,
                                     String nextOutputSummary,
                                     Instant now) {
        Instant safeNow = now == null ? Instant.now() : now;
        boolean terminal = nextStatus == RuntimeTaskStatus.COMPLETED
                || nextStatus == RuntimeTaskStatus.FAILED
                || nextStatus == RuntimeTaskStatus.CANCELLED;
        return new RuntimeTask(
                taskId,
                sessionId,
                nextStatus,
                description,
                requestMode,
                normalize(nextCurrentNode),
                engine,
                normalize(nextLastError),
                normalize(nextOutputSummary),
                createdAt,
                safeNow,
                terminal ? safeNow : finishedAt
        );
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

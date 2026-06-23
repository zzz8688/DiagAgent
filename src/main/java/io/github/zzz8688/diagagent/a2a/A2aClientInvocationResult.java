package io.github.zzz8688.diagagent.a2a;

public record A2aClientInvocationResult(
        String agentId,
        String endpoint,
        String requestId,
        String method,
        String resultType,
        String taskId,
        String contextId,
        String taskState,
        A2aPayload payload,
        String receivedAt
) {
}

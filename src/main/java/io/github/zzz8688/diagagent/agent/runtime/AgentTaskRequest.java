package io.github.zzz8688.diagagent.agent.runtime;

public record AgentTaskRequest(
        String taskId,
        String sessionId,
        String userQuestion,
        String targetAgentId,
        boolean readOnly
) {
}

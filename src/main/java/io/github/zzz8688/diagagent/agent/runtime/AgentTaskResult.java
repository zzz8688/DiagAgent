package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.agent.AgentDiagnosisResponse;

public record AgentTaskResult(
        String taskId,
        String agentId,
        AgentTaskStatus status,
        AgentDiagnosisResponse response,
        String errorMessage
) {

    public static AgentTaskResult completed(String taskId, String agentId, AgentDiagnosisResponse response) {
        return new AgentTaskResult(taskId, agentId, AgentTaskStatus.COMPLETED, response, null);
    }

    public static AgentTaskResult failed(String taskId, String agentId, String errorMessage) {
        return new AgentTaskResult(taskId, agentId, AgentTaskStatus.FAILED, null, errorMessage);
    }
}

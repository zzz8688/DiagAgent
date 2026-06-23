package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LocalSpecialistActionAdapter implements ReactActionAdapter {

    private final AgentRegistry agentRegistry;
    private final LocalAgentTaskDispatcher localAgentTaskDispatcher;

    @Value("${diagagent.experimental-specialists-enabled:false}")
    private boolean experimentalSpecialistsEnabled;

    @Override
    public ReactActionType actionType() {
        return ReactActionType.CALL_LOCAL_SPECIALIST;
    }

    @Override
    public ReactActionExecutionResult execute(ReactAction action,
                                              String userQuestion,
                                              String sessionId,
                                              boolean readOnly) {
        if (!experimentalSpecialistsEnabled) {
            return ReactActionExecutionResult.unsupported(
                    action,
                    "Local specialists are disabled by default and kept as experimental capability."
            );
        }
        AgentDescriptor descriptor = agentRegistry.findById(action.target()).orElse(null);
        if (descriptor == null) {
            return ReactActionExecutionResult.failed(action, null, "Unknown specialist target: " + action.target());
        }
        if (!descriptor.localAgent()) {
            return ReactActionExecutionResult.unsupported(action, "Specialist target is not a local agent: " + descriptor.agentId());
        }
        AgentTaskRequest request = new AgentTaskRequest(
                UUID.randomUUID().toString(),
                sessionId,
                userQuestion,
                descriptor.agentId(),
                readOnly
        );
        AgentTaskResult taskResult = localAgentTaskDispatcher.dispatch(request, descriptor);
        if (taskResult.status() == AgentTaskStatus.COMPLETED) {
            return ReactActionExecutionResult.completed(action, taskResult, "local specialist completed");
        }
        return ReactActionExecutionResult.failed(
                action,
                taskResult,
                taskResult.errorMessage() == null ? "local specialist failed" : taskResult.errorMessage()
        );
    }
}

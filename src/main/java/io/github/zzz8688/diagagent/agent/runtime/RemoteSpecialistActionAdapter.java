package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.a2a.A2aClientAdapter;
import io.github.zzz8688.diagagent.a2a.A2aClientInvocationResult;
import io.github.zzz8688.diagagent.a2a.A2aRegistryService;
import io.github.zzz8688.diagagent.a2a.A2aSendMessageParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class RemoteSpecialistActionAdapter implements ReactActionAdapter {

    private final A2aRegistryService a2aRegistryService;
    private final A2aClientAdapter a2aClientAdapter;

    @Override
    public ReactActionType actionType() {
        return ReactActionType.CALL_REMOTE_SPECIALIST;
    }

    @Override
    public ReactActionExecutionResult execute(ReactAction action,
                                              String userQuestion,
                                              String sessionId,
                                              boolean readOnly) {
        String agentId = action.target();
        if (agentId == null || agentId.isBlank()) {
            return ReactActionExecutionResult.failed(action, null, "Remote specialist target is empty");
        }
        if (a2aRegistryService.findAgent(agentId).filter(agent -> !agent.localAgent()).isEmpty()) {
            return ReactActionExecutionResult.failed(action, null, "Unknown remote specialist target: " + agentId);
        }
        try {
            String textPayload = """
                    sessionId=%s
                    readOnly=%s
                    reason=%s
                    userQuestion=%s
                    arguments=%s
                    """.formatted(
                    sessionId,
                    readOnly,
                    action.reason(),
                    userQuestion,
                    action.arguments() == null ? Map.of() : action.arguments()
            ).trim();
            A2aSendMessageParams.Message message = new A2aSendMessageParams.Message(
                    UUID.randomUUID().toString(),
                    sessionId,
                    UUID.randomUUID().toString(),
                    A2aSendMessageParams.Role.ROLE_USER,
                    List.of(A2aSendMessageParams.Part.text(textPayload)),
                    Map.of("reactActionId", action.actionId()),
                    null,
                    List.of()
            );
            A2aClientInvocationResult result = a2aClientAdapter.sendMessage(
                    agentId,
                    new A2aSendMessageParams(
                            message,
                            new A2aSendMessageParams.Configuration(List.of("text"), 0, null, false),
                            Map.of("source", "diagagent-react-runtime")
                    )
            );
            return ReactActionExecutionResult.completed(action, result, "remote specialist invocation completed");
        } catch (Exception e) {
            return ReactActionExecutionResult.failed(action, null, "remote specialist failed: " + e.getMessage());
        }
    }
}

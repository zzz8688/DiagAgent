package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Map;
import java.util.UUID;

public record ReactAction(
        String actionId,
        ReactActionType actionType,
        String actionName,
        String target,
        Map<String, String> arguments,
        String reason,
        boolean requiresApproval
) {

    public ReactAction {
        actionId = normalize(actionId);
        actionType = actionType == null ? ReactActionType.QUERY_KNOWLEDGE : actionType;
        actionName = normalize(actionName);
        target = normalize(target);
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
        reason = normalize(reason);
    }

    public static ReactAction localSpecialist(AgentDescriptor descriptor, String reason) {
        return new ReactAction(
                UUID.randomUUID().toString(),
                ReactActionType.CALL_LOCAL_SPECIALIST,
                "call_local_specialist",
                descriptor == null ? null : descriptor.agentId(),
                descriptor == null
                        ? Map.of()
                        : Map.of(
                        "transport", descriptor.transport(),
                        "endpoint", descriptor.endpoint()
                ),
                reason,
                false
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

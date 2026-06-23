package io.github.zzz8688.diagagent.agent.runtime;

public record ReactObservation(
        String actionId,
        ReactActionType actionType,
        String source,
        ReactActionStatus status,
        Object payload,
        String summary
) {

    public ReactObservation {
        actionId = normalize(actionId);
        source = normalize(source);
        status = status == null ? ReactActionStatus.FAILED : status;
        summary = normalize(summary);
    }

    public static ReactObservation fromActionResult(ReactActionExecutionResult result,
                                                    Object payload,
                                                    String source,
                                                    String summary) {
        ReactAction action = result == null ? null : result.action();
        return new ReactObservation(
                action == null ? null : action.actionId(),
                action == null ? null : action.actionType(),
                source,
                result == null ? ReactActionStatus.FAILED : result.status(),
                payload,
                summary
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

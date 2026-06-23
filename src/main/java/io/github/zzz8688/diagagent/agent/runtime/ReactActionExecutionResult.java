package io.github.zzz8688.diagagent.agent.runtime;

public record ReactActionExecutionResult(
        ReactAction action,
        ReactActionStatus status,
        Object payload,
        String message
) {

    public ReactActionExecutionResult {
        status = status == null ? ReactActionStatus.FAILED : status;
        message = normalize(message);
    }

    public static ReactActionExecutionResult completed(ReactAction action, Object payload, String message) {
        return new ReactActionExecutionResult(action, ReactActionStatus.COMPLETED, payload, message);
    }

    public static ReactActionExecutionResult failed(ReactAction action, Object payload, String message) {
        return new ReactActionExecutionResult(action, ReactActionStatus.FAILED, payload, message);
    }

    public static ReactActionExecutionResult waiting(ReactAction action, Object payload, String message) {
        return new ReactActionExecutionResult(action, ReactActionStatus.WAITING, payload, message);
    }

    public static ReactActionExecutionResult unsupported(ReactAction action, String message) {
        return new ReactActionExecutionResult(action, ReactActionStatus.UNSUPPORTED, null, message);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

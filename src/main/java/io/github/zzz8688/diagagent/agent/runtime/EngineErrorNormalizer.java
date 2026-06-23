package io.github.zzz8688.diagagent.agent.runtime;

public final class EngineErrorNormalizer {

    private EngineErrorNormalizer() {
    }

    public static String codeFor(Throwable throwable) {
        if (throwable == null) {
            return "engine_error";
        }
        String simpleName = throwable.getClass().getSimpleName();
        if (simpleName == null || simpleName.isBlank()) {
            return "engine_error";
        }
        return simpleName
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9_]+", "_")
                .toLowerCase();
    }

    public static String messageFor(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().isBlank()) {
            return "Engine invocation failed";
        }
        return throwable.getMessage();
    }
}

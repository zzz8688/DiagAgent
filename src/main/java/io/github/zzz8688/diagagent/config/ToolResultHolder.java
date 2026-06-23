package io.github.zzz8688.diagagent.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ToolResultHolder {

    private static final Map<String, Map<String, Object>> RESULTS = new ConcurrentHashMap<>();

    private ToolResultHolder() {
    }

    public static void set(String sessionId, Map<String, Object> toolResult) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (toolResult == null) {
            RESULTS.remove(sessionId);
            return;
        }
        RESULTS.put(sessionId, toolResult);
    }

    public static Map<String, Object> get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        return RESULTS.get(sessionId);
    }

    public static void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        RESULTS.remove(sessionId);
    }
}

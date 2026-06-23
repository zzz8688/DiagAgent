package io.github.zzz8688.diagagent.agent.runtime;

import java.util.Map;

public interface StopHook {

    default String name() {
        return getClass().getSimpleName();
    }

    StopHookResult evaluate(ReactTurnState turnState);

    record StopHookResult(
            boolean continueAllowed,
            StopReason stopReason,
            Map<String, Object> metadata
    ) {

        public StopHookResult {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            stopReason = stopReason == null ? StopReason.NONE : stopReason;
        }

        public static StopHookResult continueLoop() {
            return new StopHookResult(true, StopReason.NONE, Map.of());
        }

        public static StopHookResult stop(StopReason stopReason, Map<String, Object> metadata) {
            return new StopHookResult(false, stopReason, metadata);
        }
    }
}

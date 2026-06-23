package io.github.zzz8688.diagagent.sandbox.permission;

import java.util.Map;

public record SandboxPermissionDecision(
        SandboxDecisionType type,
        String reason,
        String matchedRule,
        Map<String, Object> normalizedInput,
        boolean sandboxRequired
) {
    public SandboxPermissionDecision {
        normalizedInput = normalizedInput == null ? Map.of() : Map.copyOf(normalizedInput);
    }
}

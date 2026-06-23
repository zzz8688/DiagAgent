package io.github.zzz8688.diagagent.sandbox.permission;

import java.util.List;

public record SandboxPermissionContext(
        SandboxPermissionMode mode,
        String sessionId,
        String userId,
        String workingDirectory,
        List<SandboxPermissionRule> allowRules,
        List<SandboxPermissionRule> denyRules,
        List<SandboxPermissionRule> askRules,
        boolean awaitAutomatedChecksBeforeDialog,
        boolean managedPolicyOnly,
        boolean autoModeActive
) {
    public SandboxPermissionContext {
        allowRules = allowRules == null ? List.of() : List.copyOf(allowRules);
        denyRules = denyRules == null ? List.of() : List.copyOf(denyRules);
        askRules = askRules == null ? List.of() : List.copyOf(askRules);
    }
}

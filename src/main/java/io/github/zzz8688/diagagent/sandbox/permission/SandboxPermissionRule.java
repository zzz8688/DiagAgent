package io.github.zzz8688.diagagent.sandbox.permission;

public record SandboxPermissionRule(
        SandboxRuleAction action,
        String toolSelector,
        String targetPattern,
        SandboxRuleSource source,
        String reason,
        boolean readOnly
) {
}

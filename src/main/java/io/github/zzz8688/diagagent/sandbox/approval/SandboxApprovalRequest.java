package io.github.zzz8688.diagagent.sandbox.approval;

import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionDecision;

import java.util.Map;

public record SandboxApprovalRequest(
        String requestId,
        String sessionId,
        String userId,
        String targetType,
        String targetName,
        Map<String, Object> arguments,
        SandboxPermissionDecision permissionDecision,
        SandboxApprovalBackend preferredBackend
) {
    public SandboxApprovalRequest {
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}

package io.github.zzz8688.diagagent.sandbox.execution;

import io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalResult;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionDecision;

public record SandboxProtectedActionEnvelope<T>(
        SandboxPermissionDecision permissionDecision,
        SandboxApprovalResult approvalResult,
        T result,
        String errorMessage
) {
    public boolean success() {
        return errorMessage == null || errorMessage.isBlank();
    }
}

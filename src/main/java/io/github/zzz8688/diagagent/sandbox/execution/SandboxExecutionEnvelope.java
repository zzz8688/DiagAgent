package io.github.zzz8688.diagagent.sandbox.execution;

import io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalResult;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionDecision;

public record SandboxExecutionEnvelope(
        SandboxPermissionDecision permissionDecision,
        SandboxApprovalResult approvalResult,
        SandboxExecutionResult executionResult
) {
}

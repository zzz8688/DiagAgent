package io.github.zzz8688.diagagent.sandbox.approval;

public record SandboxApprovalResult(
        SandboxApprovalStatus status,
        SandboxApprovalBackend backend,
        String reason
) {
    public boolean approved() {
        return status == SandboxApprovalStatus.NOT_REQUIRED || status == SandboxApprovalStatus.APPROVED;
    }
}

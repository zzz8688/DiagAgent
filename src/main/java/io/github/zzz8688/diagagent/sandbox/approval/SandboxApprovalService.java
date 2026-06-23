package io.github.zzz8688.diagagent.sandbox.approval;

import io.github.zzz8688.diagagent.sandbox.SandboxProperties;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxDecisionType;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionDecision;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SandboxApprovalService {

    private final SandboxProperties sandboxProperties;

    public SandboxApprovalResult resolve(SandboxApprovalRequest request) {
        SandboxPermissionDecision decision = request.permissionDecision();
        if (decision == null) {
            return new SandboxApprovalResult(
                    SandboxApprovalStatus.DENIED,
                    sandboxProperties.getApprovalBackend(),
                    "Missing permission decision."
            );
        }
        if (decision.type() == SandboxDecisionType.DENY) {
            return new SandboxApprovalResult(
                    SandboxApprovalStatus.DENIED,
                    sandboxProperties.getApprovalBackend(),
                    decision.reason()
            );
        }
        if (decision.type() == SandboxDecisionType.ALLOW) {
            return new SandboxApprovalResult(
                    SandboxApprovalStatus.NOT_REQUIRED,
                    sandboxProperties.getApprovalBackend(),
                    "Approval not required."
            );
        }
        if (sandboxProperties.isAutoApproveAskRequests()) {
            return new SandboxApprovalResult(
                    SandboxApprovalStatus.APPROVED,
                    sandboxProperties.getApprovalBackend(),
                    "ASK decision auto-approved by sandbox.auto-approve-ask-requests."
            );
        }
        return new SandboxApprovalResult(
                SandboxApprovalStatus.DENIED,
                sandboxProperties.getApprovalBackend(),
                "ASK decision requires approval, but no interactive approval backend is enabled."
        );
    }
}

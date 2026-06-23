package io.github.zzz8688.diagagent.sandbox.audit;

import java.util.List;

public record SandboxAuditRecord(
        String executionId,
        String sessionId,
        String taskId,
        String userId,
        String executionMode,
        String toolName,
        String targetType,
        String targetName,
        String workingDirectory,
        String argumentDigest,
        String permissionDecision,
        String approvalStatus,
        String approvalBackend,
        String matchedRule,
        String sandboxProfile,
        boolean approved,
        boolean success,
        boolean timedOut,
        boolean cancelled,
        int exitCode,
        String resultLabel,
        String stdoutDigest,
        String stderrDigest,
        int artifactCount,
        List<SandboxArtifactRef> artifactRefs,
        String createdAt
) {
    public SandboxAuditRecord {
        artifactRefs = artifactRefs == null ? List.of() : List.copyOf(artifactRefs);
    }

    public record SandboxArtifactRef(
            String kind,
            String name,
            String reference,
            String digest
    ) {
    }
}

package io.github.zzz8688.diagagent.sandbox.execution;

import io.github.zzz8688.diagagent.agent.runtime.ExecutionEventBus;
import io.github.zzz8688.diagagent.agent.runtime.ExecutionEventType;
import io.github.zzz8688.diagagent.config.SessionContext;
import io.github.zzz8688.diagagent.sandbox.SandboxProperties;
import io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalRequest;
import io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalResult;
import io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalService;
import io.github.zzz8688.diagagent.sandbox.audit.SandboxAuditRecord;
import io.github.zzz8688.diagagent.sandbox.audit.SandboxAuditService;
import io.github.zzz8688.diagagent.sandbox.backend.SandboxBackend;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxDecisionType;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionDecision;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionRequest;
import io.github.zzz8688.diagagent.sandbox.permission.SandboxPermissionService;
import io.github.zzz8688.diagagent.util.JwtContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

@Service
@RequiredArgsConstructor
public class SandboxExecutionService {

    private final SandboxProperties sandboxProperties;
    private final SandboxPermissionService sandboxPermissionService;
    private final SandboxApprovalService sandboxApprovalService;
    private final SandboxAuditService sandboxAuditService;
    private final List<SandboxBackend> sandboxBackends;
    private final ExecutionEventBus executionEventBus;

    public SandboxExecutionEnvelope executeCommand(String toolName,
                                                   String command,
                                                   String workingDirectory) {
        String executionId = UUID.randomUUID().toString();
        SandboxExecutionRequest executionRequest = new SandboxExecutionRequest(
                executionId,
                SandboxTargetType.COMMAND,
                "powershell",
                Map.of("command", command == null ? "" : command.trim()),
                workingDirectory,
                sandboxProperties.toSandboxProfile().maxWallClockTime(),
                sandboxProperties.toSandboxProfile()
        );
        return executeSandboxRequest("COMMAND_EXECUTION", toolName, executionRequest);
    }

    public SandboxExecutionEnvelope executeScript(String toolName,
                                                  String scriptPath,
                                                  String command,
                                                  String workingDirectory,
                                                  Map<String, Object> arguments) {
        String executionId = UUID.randomUUID().toString();
        Map<String, Object> executionArguments = new LinkedHashMap<>();
        executionArguments.put("command", command == null ? "" : command.trim());
        if (arguments != null && !arguments.isEmpty()) {
            executionArguments.putAll(arguments);
        }
        SandboxExecutionRequest executionRequest = new SandboxExecutionRequest(
                executionId,
                SandboxTargetType.SCRIPT,
                scriptPath == null ? "" : scriptPath.trim(),
                Map.copyOf(executionArguments),
                workingDirectory,
                sandboxProperties.toSandboxProfile().maxWallClockTime(),
                sandboxProperties.toSandboxProfile()
        );
        return executeSandboxRequest("SCRIPT_EXECUTION", toolName, executionRequest);
    }

    private SandboxExecutionEnvelope executeSandboxRequest(String executionMode,
                                                           String toolName,
                                                           SandboxExecutionRequest executionRequest) {
        SandboxPermissionDecision permissionDecision = evaluatePermission(
                executionRequest.executionId(),
                toolName,
                executionRequest.targetType(),
                executionRequest.targetName(),
                executionRequest.arguments(),
                executionRequest.workingDirectory()
        );
        SandboxApprovalResult approvalResult = resolveApproval(
                executionRequest.executionId(),
                executionRequest.targetType(),
                executionRequest.targetName(),
                executionRequest.arguments(),
                permissionDecision
        );

        SandboxExecutionResult executionResult;
        if (permissionDecision.type() == SandboxDecisionType.DENY) {
            executionResult = SandboxExecutionResult.failed(executionRequest.executionId(), permissionDecision.reason());
        } else if (!approvalResult.approved()) {
            executionResult = SandboxExecutionResult.failed(executionRequest.executionId(), approvalResult.reason());
        } else {
            SandboxBackend backend = resolveBackend();
            executionResult = backend.execute(executionRequest);
        }

        recordAudit(
                executionRequest.executionId(),
                executionMode,
                toolName,
                executionRequest.targetType(),
                executionRequest.targetName(),
                executionRequest.arguments(),
                executionRequest.workingDirectory(),
                permissionDecision,
                approvalResult,
                executionRequest.profile().profileName(),
                executionResult.success(),
                executionResult.timedOut(),
                executionResult.cancelled(),
                executionResult.exitCode(),
                executionResult.stdout(),
                executionResult.stderr()
        );

        return new SandboxExecutionEnvelope(permissionDecision, approvalResult, executionResult);
    }

    public <T> SandboxProtectedActionEnvelope<T> executeProtectedAction(String toolName,
                                                                        SandboxTargetType targetType,
                                                                        String targetName,
                                                                        Map<String, Object> arguments,
                                                                        String workingDirectory,
                                                                        Callable<T> action) {
        if (SessionContext.inProtectedExecution()) {
            try {
                return new SandboxProtectedActionEnvelope<>(
                        new SandboxPermissionDecision(
                                SandboxDecisionType.ALLOW,
                                "nested-protected-execution",
                                "nested-protected-execution",
                                Map.of(),
                                true
                        ),
                        new SandboxApprovalResult(
                                io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalStatus.NOT_REQUIRED,
                                io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalBackend.LOCAL_UI,
                                "nested-protected-execution"
                        ),
                        action.call(),
                        null
                );
            } catch (Exception e) {
                String message = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                return new SandboxProtectedActionEnvelope<>(
                        new SandboxPermissionDecision(
                                SandboxDecisionType.ALLOW,
                                "nested-protected-execution",
                                "nested-protected-execution",
                                Map.of(),
                                true
                        ),
                        new SandboxApprovalResult(
                                io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalStatus.NOT_REQUIRED,
                                io.github.zzz8688.diagagent.sandbox.approval.SandboxApprovalBackend.LOCAL_UI,
                                "nested-protected-execution"
                        ),
                        null,
                        message
                );
            }
        }
        String executionId = UUID.randomUUID().toString();
        SandboxPermissionDecision permissionDecision = evaluatePermission(
                executionId,
                toolName,
                targetType,
                targetName,
                arguments,
                workingDirectory
        );
        SandboxApprovalResult approvalResult = resolveApproval(
                executionId,
                targetType,
                targetName,
                arguments,
                permissionDecision
        );

        T result = null;
        String errorMessage = null;
        if (permissionDecision.type() == SandboxDecisionType.DENY) {
            errorMessage = permissionDecision.reason();
        } else if (!approvalResult.approved()) {
            errorMessage = approvalResult.reason();
        } else {
            SessionContext.enterProtectedExecution();
            try {
                result = action.call();
            } catch (Exception e) {
                errorMessage = e.getMessage() == null || e.getMessage().isBlank()
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
            } finally {
                SessionContext.exitProtectedExecution();
            }
        }

        recordAudit(
                executionId,
                "PROTECTED_ACTION",
                toolName,
                targetType,
                targetName,
                arguments,
                workingDirectory,
                permissionDecision,
                approvalResult,
                sandboxProperties.toSandboxProfile().profileName(),
                errorMessage == null || errorMessage.isBlank(),
                false,
                false,
                errorMessage == null || errorMessage.isBlank() ? 0 : -1,
                result == null ? "" : String.valueOf(result),
                errorMessage == null ? "" : errorMessage
        );
        return new SandboxProtectedActionEnvelope<>(permissionDecision, approvalResult, result, errorMessage);
    }

    private SandboxPermissionDecision evaluatePermission(String executionId,
                                                         String toolName,
                                                         SandboxTargetType targetType,
                                                         String targetName,
                                                         Map<String, Object> arguments,
                                                         String workingDirectory) {
        SandboxPermissionDecision decision = sandboxPermissionService.evaluate(
                new SandboxPermissionRequest(
                        toolName,
                        targetType,
                        targetName,
                        arguments,
                        workingDirectory
                )
        );
        publishSandboxEvent(
                ExecutionEventType.SANDBOX_PERMISSION_DECIDED,
                executionId,
                toolName,
                targetType,
                targetName,
                Map.of(
                        "permissionDecision", safe(decision.type() == null ? null : decision.type().name()),
                        "reason", safe(decision.reason()),
                        "matchedRule", safe(decision.matchedRule()),
                        "sandboxRequired", Boolean.toString(decision.sandboxRequired()),
                        "workingDirectory", safe(workingDirectory)
                )
        );
        return decision;
    }

    private SandboxApprovalResult resolveApproval(String executionId,
                                                  SandboxTargetType targetType,
                                                  String targetName,
                                                  Map<String, Object> arguments,
                                                  SandboxPermissionDecision permissionDecision) {
        SandboxApprovalResult result = sandboxApprovalService.resolve(
                new SandboxApprovalRequest(
                        executionId,
                        SessionContext.getSessionId(),
                        currentUserName(),
                        targetType.name(),
                        targetName,
                        arguments,
                        permissionDecision,
                        sandboxProperties.getApprovalBackend()
                )
        );
        publishSandboxEvent(
                ExecutionEventType.SANDBOX_APPROVAL_RESOLVED,
                executionId,
                "sandbox-approval",
                targetType,
                targetName,
                Map.of(
                        "permissionDecision", safe(permissionDecision == null || permissionDecision.type() == null ? null : permissionDecision.type().name()),
                        "approvalStatus", safe(result.status() == null ? null : result.status().name()),
                        "approvalBackend", safe(result.backend() == null ? null : result.backend().name()),
                        "approved", Boolean.toString(result.approved()),
                        "reason", safe(result.reason())
                )
        );
        return result;
    }

    private void recordAudit(String executionId,
                             String executionMode,
                             String toolName,
                             SandboxTargetType targetType,
                             String targetName,
                             Map<String, Object> arguments,
                             String workingDirectory,
                             SandboxPermissionDecision permissionDecision,
                             SandboxApprovalResult approvalResult,
                             String profileName,
                             boolean success,
                             boolean timedOut,
                             boolean cancelled,
                             int exitCode,
                             String stdout,
                             String stderr) {
        List<SandboxAuditRecord.SandboxArtifactRef> artifactRefs = buildArtifactRefs(targetType, targetName, stdout, stderr);
        String argumentDigest = digest(arguments == null ? "" : String.valueOf(arguments));
        String resultLabel = sandboxExecutionResult(success, timedOut, cancelled);
        sandboxAuditService.record(new SandboxAuditRecord(
                executionId,
                SessionContext.getSessionId(),
                SessionContext.getTaskId(),
                currentUserName(),
                executionMode,
                toolName,
                targetType.name(),
                targetName,
                safe(workingDirectory),
                argumentDigest,
                permissionDecision.type().name(),
                approvalResult.status().name(),
                approvalResult.backend().name(),
                permissionDecision.matchedRule(),
                profileName,
                approvalResult.approved(),
                success,
                timedOut,
                cancelled,
                exitCode,
                resultLabel,
                digest(stdout),
                digest(stderr),
                artifactRefs.size(),
                artifactRefs,
                Instant.now().toString()
        ));
        publishSandboxEvent(
                ExecutionEventType.SANDBOX_EXECUTION_RECORDED,
                executionId,
                toolName,
                targetType,
                targetName,
                sandboxExecutionPayload(
                        executionMode,
                        workingDirectory,
                        argumentDigest,
                        artifactRefs,
                        permissionDecision,
                        approvalResult,
                        profileName,
                        success,
                        timedOut,
                        cancelled,
                        exitCode,
                        stdout,
                        stderr
                )
        );
    }

    private void publishSandboxEvent(ExecutionEventType eventType,
                                     String executionId,
                                     String toolName,
                                     SandboxTargetType targetType,
                                     String targetName,
                                     Map<String, String> payload) {
        Map<String, String> eventPayload = new LinkedHashMap<>();
        eventPayload.put("executionId", safe(executionId));
        eventPayload.put("toolName", safe(toolName));
        eventPayload.put("targetType", targetType == null ? "" : targetType.name());
        eventPayload.put("targetName", safe(targetName));
        eventPayload.put("sessionId", safe(SessionContext.getSessionId()));
        eventPayload.put("taskId", safe(SessionContext.getTaskId()));
        if (payload != null && !payload.isEmpty()) {
            payload.forEach((key, value) -> eventPayload.put(safe(key), safe(value)));
        }
        executionEventBus.publish(
                eventType,
                SessionContext.getTaskId(),
                SessionContext.getSessionId(),
                "sandbox-execution",
                Map.copyOf(eventPayload)
        );
    }

    private Map<String, String> sandboxExecutionPayload(String executionMode,
                                                        String workingDirectory,
                                                        String argumentDigest,
                                                        List<SandboxAuditRecord.SandboxArtifactRef> artifactRefs,
                                                        SandboxPermissionDecision permissionDecision,
                                                        SandboxApprovalResult approvalResult,
                                                        String profileName,
                                                        boolean success,
                                                        boolean timedOut,
                                                        boolean cancelled,
                                                        int exitCode,
                                                        String stdout,
                                                        String stderr) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("permissionDecision", safe(permissionDecision.type() == null ? null : permissionDecision.type().name()));
        payload.put("approvalStatus", safe(approvalResult.status() == null ? null : approvalResult.status().name()));
        payload.put("approvalBackend", safe(approvalResult.backend() == null ? null : approvalResult.backend().name()));
        payload.put("executionMode", safe(executionMode));
        payload.put("workingDirectory", safe(workingDirectory));
        payload.put("argumentDigest", safe(argumentDigest));
        payload.put("sandboxProfile", safe(profileName));
        payload.put("success", Boolean.toString(success));
        payload.put("timedOut", Boolean.toString(timedOut));
        payload.put("cancelled", Boolean.toString(cancelled));
        payload.put("exitCode", Integer.toString(exitCode));
        payload.put("stdoutDigest", digest(stdout));
        payload.put("stderrDigest", digest(stderr));
        payload.put("artifactCount", Integer.toString(artifactRefs == null ? 0 : artifactRefs.size()));
        payload.put("artifactKinds", artifactRefs == null || artifactRefs.isEmpty()
                ? ""
                : artifactRefs.stream().map(SandboxAuditRecord.SandboxArtifactRef::kind).distinct().reduce((left, right) -> left + "," + right).orElse(""));
        payload.put("result", sandboxExecutionResult(success, timedOut, cancelled));
        return Map.copyOf(payload);
    }

    private List<SandboxAuditRecord.SandboxArtifactRef> buildArtifactRefs(SandboxTargetType targetType,
                                                                          String targetName,
                                                                          String stdout,
                                                                          String stderr) {
        List<SandboxAuditRecord.SandboxArtifactRef> artifactRefs = new ArrayList<>();
        if (targetType != null && targetName != null && !targetName.isBlank()) {
            artifactRefs.add(new SandboxAuditRecord.SandboxArtifactRef(
                    "target",
                    targetName,
                    targetType.name() + ":" + targetName,
                    ""
            ));
        }
        if (stdout != null && !stdout.isBlank()) {
            artifactRefs.add(new SandboxAuditRecord.SandboxArtifactRef(
                    "stdout",
                    "stdout",
                    "sha256:" + digest(stdout),
                    digest(stdout)
            ));
        }
        if (stderr != null && !stderr.isBlank()) {
            artifactRefs.add(new SandboxAuditRecord.SandboxArtifactRef(
                    "stderr",
                    "stderr",
                    "sha256:" + digest(stderr),
                    digest(stderr)
            ));
        }
        return List.copyOf(artifactRefs);
    }

    private SandboxBackend resolveBackend() {
        return sandboxBackends.stream()
                .filter(SandboxBackend::available)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No available sandbox backend for current platform."));
    }

    private String currentUserName() {
        String username = JwtContextUtil.getCurrentUsername();
        return username == null ? "" : username;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String sandboxExecutionResult(boolean success, boolean timedOut, boolean cancelled) {
        if (timedOut) {
            return "TIMEOUT";
        }
        if (cancelled) {
            return "CANCELLED";
        }
        return success ? "SUCCESS" : "FAILED";
    }

    private String digest(String content) {
        String text = content == null ? "" : content;
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(messageDigest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionResult;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ShellCommandTool extends BaseTool {

    private final SandboxExecutionService sandboxExecutionService;

    @PlatformTool(description = "在受控沙箱后端中执行本地 PowerShell 命令，适合只读排查、目录检查和脚本探测。")
    @Tool("在受控沙箱后端中执行本地 PowerShell 命令，适合只读排查、目录检查和脚本探测。")
    public String runShellCommand(String command, String workingDirectory) {
        String normalizedCommand = command == null ? "" : command.trim();
        String normalizedWorkingDirectory = workingDirectory == null ? "" : workingDirectory.trim();
        beforeToolCall("runShellCommand", normalizedCommand + "|" + normalizedWorkingDirectory);
        checkCancelled();

        if (normalizedCommand.isBlank()) {
            return "命令不能为空。";
        }

        try {
            SandboxExecutionEnvelope envelope = sandboxExecutionService.executeCommand(
                    "runShellCommand",
                    normalizedCommand,
                    normalizedWorkingDirectory
            );
            return renderResult(envelope);
        } catch (BaseTool.ToolExecutionCancelledException e) {
            throw e;
        } catch (Exception e) {
            log.error("执行沙箱命令失败", e);
            return "沙箱命令执行失败: " + e.getMessage();
        }
    }

    private String renderResult(SandboxExecutionEnvelope envelope) {
        SandboxExecutionResult result = envelope.executionResult();
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("permissionDecision", envelope.permissionDecision().type());
        sections.put("permissionReason", envelope.permissionDecision().reason());
        sections.put("approvalStatus", envelope.approvalResult().status());
        sections.put("approvalReason", envelope.approvalResult().reason());
        sections.put("backendSuccess", result.success());
        sections.put("timedOut", result.timedOut());
        sections.put("cancelled", result.cancelled());
        sections.put("exitCode", result.exitCode());
        if (result.stdout() != null && !result.stdout().isBlank()) {
            sections.put("stdout", result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            sections.put("stderr", result.stderr());
        }
        if (result.outputDigest() != null && !result.outputDigest().isBlank()) {
            sections.put("outputDigest", result.outputDigest());
        }

        StringBuilder builder = new StringBuilder();
        builder.append("Sandbox command result");
        for (Map.Entry<String, Object> entry : sections.entrySet()) {
            builder.append("\n").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return builder.toString();
    }
}

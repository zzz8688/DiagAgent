package io.github.zzz8688.diagagent.tools;

import dev.langchain4j.agent.tool.Tool;
import io.github.zzz8688.diagagent.config.SessionContext;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionEnvelope;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionResult;
import io.github.zzz8688.diagagent.sandbox.execution.SandboxExecutionService;
import io.github.zzz8688.diagagent.skills.SkillExecutionSchema;
import io.github.zzz8688.diagagent.skills.SkillExecutionPlan;
import io.github.zzz8688.diagagent.skills.SkillDoc;
import io.github.zzz8688.diagagent.skills.SkillService;
import io.github.zzz8688.diagagent.tools.registry.PlatformTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class SkillExecutionTool extends BaseTool {

    private static final Pattern POWERSHELL_PARAM_BLOCK_PATTERN = Pattern.compile("(?is)\\bparam\\s*\\((.*?)\\)");
    private static final Pattern POWERSHELL_PARAM_NAME_PATTERN = Pattern.compile("\\$(\\w+)");

    private final SkillService skillService;
    private final SandboxExecutionService sandboxExecutionService;

    @PlatformTool(
            description = "执行一个已发现的 skill。输入 skillId、可选 arguments 和 context，返回技能执行计划；如 skill 声明了 scripts/**，则通过 CLI/sandbox/approval 执行并只返回最小执行摘要。",
            userVisible = false
    )
    @Tool("执行一个已发现的 skill。输入 skillId、可选 arguments 和 context，返回技能执行计划；如 skill 声明了 scripts/**，则通过 CLI/sandbox/approval 执行并只返回最小执行摘要。")
    public String runSkill(String skillId, String arguments, String context) {
        beforeToolCall("runSkill", skillId + "|" + arguments);
        checkCancelled();
        if (skillId == null || skillId.isBlank()) {
            return "skillId 不能为空。";
        }
        SkillExecutionPlan plan = skillService.buildExecutionPlan(skillId.trim(), arguments, context);
        if (plan == null) {
            return "未找到对应的技能。";
        }
        SkillDoc skill = skillService.findSkill(plan.skillId());
        String scriptExecutionSummary = renderScriptExecutionSummary(skill, plan, arguments, context);
        SkillExecutionSchema executionSchema = plan.executionSchema();
        return """
                skillId: %s
                title: %s
                executionMode: %s
                executionContext: %s
                targetAgent: %s
                allowedTools: %s
                argumentNames: %s
                scriptExecution: %s

                %s
                """.formatted(
                plan.skillId(),
                empty(plan.title()),
                executionSchema == null ? "" : empty(executionSchema.executionMode()),
                executionSchema == null ? "" : empty(executionSchema.executionContext()),
                executionSchema == null ? "" : empty(executionSchema.targetAgent()),
                plan.allowedTools().isEmpty() ? "[]" : plan.allowedTools(),
                plan.argumentNames().isEmpty() ? "[]" : plan.argumentNames(),
                scriptExecutionSummary,
                plan.prompt()
        ).trim();
    }

    private String renderScriptExecutionSummary(SkillDoc skill,
                                                SkillExecutionPlan plan,
                                                String rawArguments,
                                                String context) {
        if (skill == null) {
            return "skill-not-found";
        }
        SkillExecutionSchema executionSchema = plan.executionSchema();
        if (executionSchema == null || executionSchema.scriptFiles().isEmpty()) {
            return "not-requested";
        }

        List<ScriptExecutionSummary> summaries = new ArrayList<>();
        for (String scriptFile : executionSchema.scriptFiles()) {
            summaries.add(executeSingleScript(skill, plan, scriptFile, rawArguments, context));
        }

        long attempted = summaries.stream().filter(ScriptExecutionSummary::attempted).count();
        long succeeded = summaries.stream().filter(ScriptExecutionSummary::success).count();
        StringBuilder builder = new StringBuilder();
        builder.append("attempted=").append(attempted)
                .append(", succeeded=").append(succeeded)
                .append(", declared=").append(executionSchema.scriptFiles().size());
        for (ScriptExecutionSummary summary : summaries) {
            builder.append("\n- script=").append(summary.scriptPath())
                    .append("; attempted=").append(summary.attempted())
                    .append("; permissionDecision=").append(summary.permissionDecision())
                    .append("; approvalStatus=").append(summary.approvalStatus())
                    .append("; success=").append(summary.success());
            if (summary.exitCode() != null) {
                builder.append("; exitCode=").append(summary.exitCode());
            }
            if (summary.outputDigest() != null && !summary.outputDigest().isBlank()) {
                builder.append("; outputDigest=").append(summary.outputDigest());
            }
            if (summary.reason() != null && !summary.reason().isBlank()) {
                builder.append("; reason=").append(summary.reason());
            }
        }
        return builder.toString();
    }

    private ScriptExecutionSummary executeSingleScript(SkillDoc skill,
                                                       SkillExecutionPlan plan,
                                                       String scriptFile,
                                                       String rawArguments,
                                                       String context) {
        ResolvedScript resolvedScript = resolveScript(skill, scriptFile);
        if (resolvedScript.errorMessage() != null) {
            return ScriptExecutionSummary.skipped(scriptFile, resolvedScript.errorMessage());
        }
        if (!resolvedScript.absolutePath().toString().toLowerCase(Locale.ROOT).endsWith(".ps1")) {
            return ScriptExecutionSummary.skipped(scriptFile, "Only .ps1 scripts are currently supported by runSkill.");
        }

        List<String> parameterNames = extractPowerShellParameterNames(resolvedScript.content());
        Map<String, String> parameterValues = resolveScriptParameterValues(parameterNames, plan, rawArguments, context);
        String command = buildScriptCommand(resolvedScript.absolutePath(), parameterValues);
        Map<String, Object> executionArguments = new LinkedHashMap<>();
        executionArguments.put("skillId", plan.skillId());
        executionArguments.put("scriptPath", resolvedScript.scriptPath());
        executionArguments.put("parameterNames", parameterValues.keySet());

        try {
            SandboxExecutionEnvelope envelope = sandboxExecutionService.executeScript(
                    "runSkill",
                    resolvedScript.scriptPath(),
                    command,
                    resolvedScript.workingDirectory(),
                    executionArguments
            );
            SandboxExecutionResult result = envelope.executionResult();
            String reason = firstNonBlank(
                    result.stderr(),
                    envelope.approvalResult() == null ? null : envelope.approvalResult().reason(),
                    envelope.permissionDecision() == null ? null : envelope.permissionDecision().reason()
            );
            return new ScriptExecutionSummary(
                    resolvedScript.scriptPath(),
                    true,
                    envelope.permissionDecision() == null || envelope.permissionDecision().type() == null
                            ? ""
                            : envelope.permissionDecision().type().name(),
                    envelope.approvalResult() == null || envelope.approvalResult().status() == null
                            ? ""
                            : envelope.approvalResult().status().name(),
                    result != null && result.success(),
                    result == null ? null : result.exitCode(),
                    result == null ? "" : empty(result.outputDigest()),
                    reason
            );
        } catch (Exception e) {
            log.error("Skill script execution failed: skillId={}, script={}", plan.skillId(), resolvedScript.scriptPath(), e);
            return ScriptExecutionSummary.skipped(scriptFile, "skill script execution failed: " + e.getMessage());
        }
    }

    private ResolvedScript resolveScript(SkillDoc skill, String scriptPath) {
        String normalizedScriptPath = normalizeScriptPath(scriptPath);
        if (normalizedScriptPath == null) {
            return ResolvedScript.failed(scriptPath, "invalid script path");
        }

        if ("bundled".equalsIgnoreCase(skill.loadedFrom())) {
            String bundledContent = skill.bundledScripts().get(normalizedScriptPath);
            if (bundledContent == null || bundledContent.isBlank()) {
                return ResolvedScript.failed(normalizedScriptPath, "bundled script content is missing");
            }
            return ResolvedScript.failed(normalizedScriptPath, "bundled skill scripts are not yet materialized into sandbox-executable files");
        }

        if (skill.rootPath() == null || skill.rootPath().isBlank()) {
            return ResolvedScript.failed(normalizedScriptPath, "skill root path is empty");
        }

        try {
            Path rootPath = Paths.get(skill.rootPath()).toAbsolutePath().normalize();
            Path scriptAbsolutePath = rootPath.resolve(normalizedScriptPath).normalize();
            if (!scriptAbsolutePath.startsWith(rootPath)) {
                return ResolvedScript.failed(normalizedScriptPath, "script path escapes skill root");
            }
            if (!Files.exists(scriptAbsolutePath) || !Files.isRegularFile(scriptAbsolutePath)) {
                return ResolvedScript.failed(normalizedScriptPath, "script file does not exist");
            }
            String content = Files.readString(scriptAbsolutePath, StandardCharsets.UTF_8);
            return new ResolvedScript(
                    normalizedScriptPath,
                    scriptAbsolutePath,
                    rootPath.toString(),
                    content,
                    null
            );
        } catch (IOException e) {
            log.error("Failed to resolve skill script: skillId={}, script={}", skill.id(), normalizedScriptPath, e);
            return ResolvedScript.failed(normalizedScriptPath, "failed to read script: " + e.getMessage());
        }
    }

    private List<String> extractPowerShellParameterNames(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        Matcher blockMatcher = POWERSHELL_PARAM_BLOCK_PATTERN.matcher(content);
        if (!blockMatcher.find()) {
            return List.of();
        }
        String block = blockMatcher.group(1);
        Matcher nameMatcher = POWERSHELL_PARAM_NAME_PATTERN.matcher(block);
        List<String> parameterNames = new ArrayList<>();
        while (nameMatcher.find()) {
            String parameterName = nameMatcher.group(1);
            if (parameterName == null || parameterName.isBlank()) {
                continue;
            }
            boolean exists = parameterNames.stream().anyMatch(existing -> existing.equalsIgnoreCase(parameterName));
            if (!exists) {
                parameterNames.add(parameterName);
            }
        }
        return List.copyOf(parameterNames);
    }

    private Map<String, String> resolveScriptParameterValues(List<String> parameterNames,
                                                             SkillExecutionPlan plan,
                                                             String rawArguments,
                                                             String context) {
        if (parameterNames == null || parameterNames.isEmpty()) {
            return Map.of();
        }
        Map<String, String> candidates = new LinkedHashMap<>();
        plan.resolvedArguments().forEach((key, value) -> candidates.put(normalizeKey(key), empty(value)));

        String primaryText = firstNonBlank(
                rawArguments,
                context,
                plan.resolvedArguments().get("input")
        );
        putCandidate(candidates, "sessionId", SessionContext.getSessionId());
        putCandidate(candidates, "taskId", SessionContext.getTaskId());
        putCandidate(candidates, "skillId", plan.skillId());
        putCandidate(candidates, "context", context);
        putCandidate(candidates, "arguments", rawArguments);
        putCandidate(candidates, "input", rawArguments);
        putCandidate(candidates, "keyword", primaryText);
        putCandidate(candidates, "incidentText", primaryText);
        putCandidate(candidates, "issueText", primaryText);

        Map<String, String> resolved = new LinkedHashMap<>();
        for (String parameterName : parameterNames) {
            String value = candidates.get(normalizeKey(parameterName));
            if (value != null && !value.isBlank()) {
                resolved.put(parameterName, value);
            }
        }
        return Map.copyOf(resolved);
    }

    private void putCandidate(Map<String, String> candidates, String key, String value) {
        if (key == null || key.isBlank() || value == null || value.isBlank()) {
            return;
        }
        candidates.putIfAbsent(normalizeKey(key), value.trim());
    }

    private String buildScriptCommand(Path scriptPath, Map<String, String> parameterValues) {
        StringBuilder builder = new StringBuilder();
        builder.append("& ").append(quotePowerShell(scriptPath.toString()));
        parameterValues.forEach((name, value) -> builder.append(" -").append(name).append(" ").append(quotePowerShell(value)));
        return builder.toString();
    }

    private String quotePowerShell(String value) {
        return "'" + empty(value).replace("'", "''") + "'";
    }

    private String normalizeScriptPath(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.contains("..")) {
            return null;
        }
        return normalized;
    }

    private String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }

    private record ResolvedScript(String scriptPath,
                                  Path absolutePath,
                                  String workingDirectory,
                                  String content,
                                  String errorMessage) {

        private static ResolvedScript failed(String scriptPath, String errorMessage) {
            return new ResolvedScript(
                    scriptPath == null ? "" : scriptPath,
                    Path.of("."),
                    "",
                    "",
                    errorMessage
            );
        }
    }

    private record ScriptExecutionSummary(String scriptPath,
                                          boolean attempted,
                                          String permissionDecision,
                                          String approvalStatus,
                                          boolean success,
                                          Integer exitCode,
                                          String outputDigest,
                                          String reason) {

        private static ScriptExecutionSummary skipped(String scriptPath, String reason) {
            return new ScriptExecutionSummary(
                    scriptPath == null ? "" : scriptPath,
                    false,
                    "",
                    "",
                    false,
                    null,
                    "",
                    reason
            );
        }
    }
}

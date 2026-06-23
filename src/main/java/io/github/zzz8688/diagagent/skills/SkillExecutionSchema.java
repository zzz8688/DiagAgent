package io.github.zzz8688.diagagent.skills;

import java.util.List;
import java.util.Map;

public record SkillExecutionSchema(
        String executionMode,
        String executionContext,
        String targetAgent,
        String modelSelector,
        Map<String, Object> hooks,
        List<String> scriptFiles,
        String schemaSource
) {

    public SkillExecutionSchema {
        executionMode = executionMode == null || executionMode.isBlank() ? "inline-prompt-plan" : executionMode;
        executionContext = executionContext == null || executionContext.isBlank() ? "inline" : executionContext;
        targetAgent = targetAgent == null ? "" : targetAgent;
        modelSelector = modelSelector == null ? "" : modelSelector;
        hooks = hooks == null ? Map.of() : Map.copyOf(hooks);
        scriptFiles = scriptFiles == null ? List.of() : List.copyOf(scriptFiles);
        schemaSource = schemaSource == null || schemaSource.isBlank() ? "platform-default" : schemaSource;
    }

    public static SkillExecutionSchema platformDefault(List<String> scriptFiles) {
        boolean hasScripts = scriptFiles != null && !scriptFiles.isEmpty();
        return new SkillExecutionSchema(
                hasScripts ? "sandbox-script" : "inline-prompt-plan",
                "inline",
                "",
                "",
                Map.of(),
                scriptFiles,
                "platform-default"
        );
    }
}

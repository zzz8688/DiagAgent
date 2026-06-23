package io.github.zzz8688.diagagent.skills;

import java.util.List;
import java.util.Map;

public record SkillExecutionPlan(
        String skillId,
        String title,
        String description,
        String whenToUse,
        List<String> allowedTools,
        String argumentHint,
        List<String> argumentNames,
        Map<String, String> resolvedArguments,
        String version,
        boolean userInvocable,
        List<String> resourceFiles,
        SkillExecutionSchema executionSchema,
        String prompt
) {
    public SkillExecutionPlan {
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        resolvedArguments = resolvedArguments == null ? Map.of() : Map.copyOf(resolvedArguments);
        resourceFiles = resourceFiles == null ? List.of() : List.copyOf(resourceFiles);
        executionSchema = executionSchema == null ? SkillExecutionSchema.platformDefault(List.of()) : executionSchema;
    }
}

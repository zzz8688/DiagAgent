package io.github.zzz8688.diagagent.skills;

import java.util.List;
import java.util.Map;

public record SkillDoc(
        String id,
        String title,
        String description,
        String whenToUse,
        String body,
        String sourcePath,
        String rootPath,
        List<String> resourceFiles,
        List<String> allowedTools,
        String argumentHint,
        List<String> argumentNames,
        String version,
        boolean userInvocable,
        String source,
        String loadedFrom,
        boolean userVisible,
        Map<String, String> bundledResources,
        Map<String, String> bundledScripts,
        SkillExecutionSchema executionSchema,
        Map<String, Object> metadata
) {

    public SkillDoc {
        resourceFiles = resourceFiles == null ? List.of() : List.copyOf(resourceFiles);
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        argumentNames = argumentNames == null ? List.of() : List.copyOf(argumentNames);
        bundledResources = bundledResources == null ? Map.of() : Map.copyOf(bundledResources);
        bundledScripts = bundledScripts == null ? Map.of() : Map.copyOf(bundledScripts);
        executionSchema = executionSchema == null ? SkillExecutionSchema.platformDefault(List.of()) : executionSchema;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

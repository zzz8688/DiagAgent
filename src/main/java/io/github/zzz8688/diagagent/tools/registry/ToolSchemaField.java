package io.github.zzz8688.diagagent.tools.registry;

public record ToolSchemaField(
        String name,
        String type,
        boolean required,
        String description
) {
}

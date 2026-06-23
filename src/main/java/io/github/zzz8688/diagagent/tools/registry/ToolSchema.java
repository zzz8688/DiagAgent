package io.github.zzz8688.diagagent.tools.registry;

import java.util.List;

public record ToolSchema(
        List<ToolSchemaField> fields
) {

    public ToolSchema {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static ToolSchema empty() {
        return new ToolSchema(List.of());
    }
}

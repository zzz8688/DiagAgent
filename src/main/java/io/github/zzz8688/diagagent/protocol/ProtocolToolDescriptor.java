package io.github.zzz8688.diagagent.protocol;

import java.util.Map;

public record ProtocolToolDescriptor(
        String name,
        String description,
        boolean enabled,
        boolean userVisible,
        Object inputSchema,
        Object outputSchema,
        Object annotations,
        Map<String, Object> metadata
) {

    public ProtocolToolDescriptor(String name, String description, boolean enabled, boolean userVisible) {
        this(name, description, enabled, userVisible, null, null, null, Map.of());
    }
}

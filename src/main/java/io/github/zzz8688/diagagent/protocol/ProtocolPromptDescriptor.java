package io.github.zzz8688.diagagent.protocol;

import java.util.List;
import java.util.Map;

public record ProtocolPromptDescriptor(
        String name,
        String description,
        boolean userVisible,
        List<Map<String, Object>> arguments,
        Map<String, Object> metadata
) {

    public ProtocolPromptDescriptor(String name, String description, boolean userVisible) {
        this(name, description, userVisible, List.of(), Map.of());
    }
}

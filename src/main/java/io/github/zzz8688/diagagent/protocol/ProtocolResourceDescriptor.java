package io.github.zzz8688.diagagent.protocol;

import java.util.Map;

public record ProtocolResourceDescriptor(
        String uri,
        String name,
        String description,
        String mimeType,
        boolean userVisible,
        Map<String, Object> metadata
) {

    public ProtocolResourceDescriptor(String uri, String name, String description, String mimeType, boolean userVisible) {
        this(uri, name, description, mimeType, userVisible, Map.of());
    }
}

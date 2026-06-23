package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aMessage(
        String messageId,
        String contextId,
        String taskId,
        A2aSendMessageParams.Role role,
        List<A2aSendMessageParams.Part> parts,
        Map<String, Object> metadata,
        List<String> extensions,
        List<String> referenceTaskIds
) implements A2aPayload {

    public A2aMessage {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (role == null) {
            throw new IllegalArgumentException("role must not be null");
        }
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts must not be empty");
        }
        parts = List.copyOf(parts);
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
        extensions = extensions == null || extensions.isEmpty() ? null : List.copyOf(extensions);
        referenceTaskIds = referenceTaskIds == null || referenceTaskIds.isEmpty() ? null : List.copyOf(referenceTaskIds);
    }

    @Override
    public String payloadType() {
        return "message";
    }
}

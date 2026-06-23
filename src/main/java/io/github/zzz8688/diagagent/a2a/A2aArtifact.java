package io.github.zzz8688.diagagent.a2a;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record A2aArtifact(
        String artifactId,
        String name,
        String description,
        List<A2aSendMessageParams.Part> parts,
        Map<String, Object> metadata,
        List<String> extensions
) implements A2aPayload {

    public A2aArtifact {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("artifactId must not be blank");
        }
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts must not be empty");
        }
        parts = List.copyOf(parts);
        metadata = metadata == null || metadata.isEmpty() ? null : Map.copyOf(metadata);
        extensions = extensions == null || extensions.isEmpty() ? null : List.copyOf(extensions);
    }

    @Override
    public String payloadType() {
        return "artifact";
    }
}

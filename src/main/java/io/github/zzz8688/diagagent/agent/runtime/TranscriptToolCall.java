package io.github.zzz8688.diagagent.agent.runtime;

public record TranscriptToolCall(
        String id,
        String name,
        String arguments
) {

    public TranscriptToolCall {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        arguments = arguments == null ? "" : arguments;
    }
}

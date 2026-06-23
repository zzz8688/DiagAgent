package io.github.zzz8688.diagagent.agent.runtime;

import java.util.List;
import java.util.Map;

public record TranscriptMessage(
        TranscriptMessageRole role,
        String content,
        String frameworkType,
        String toolName,
        String toolCallId,
        List<TranscriptToolCall> toolCalls,
        String frameworkMessageJson,
        Map<String, String> metadata
) {

    public TranscriptMessage {
        role = role == null ? TranscriptMessageRole.UNKNOWN : role;
        content = content == null ? "" : content;
        frameworkType = frameworkType == null ? "" : frameworkType;
        toolName = toolName == null ? "" : toolName;
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        frameworkMessageJson = frameworkMessageJson == null ? "" : frameworkMessageJson;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}

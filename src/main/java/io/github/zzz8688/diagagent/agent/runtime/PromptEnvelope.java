package io.github.zzz8688.diagagent.agent.runtime;

import java.util.List;

public record PromptEnvelope(
        List<PromptMessage> messages,
        String promptMode
) {

    public PromptEnvelope {
        messages = messages == null ? List.of() : List.copyOf(messages);
        promptMode = promptMode == null ? "" : promptMode;
    }

    public static PromptEnvelope of(List<PromptMessage> messages, String promptMode) {
        return new PromptEnvelope(messages, promptMode);
    }

    public static PromptEnvelope systemAndUser(String systemPrompt, String userPrompt, String promptMode) {
        return new PromptEnvelope(
                List.of(
                        PromptMessage.system(systemPrompt),
                        PromptMessage.user(userPrompt)
                ),
                promptMode
        );
    }
}

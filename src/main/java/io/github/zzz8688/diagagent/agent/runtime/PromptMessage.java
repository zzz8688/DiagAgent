package io.github.zzz8688.diagagent.agent.runtime;

public record PromptMessage(
        PromptMessageRole role,
        String content
) {

    public PromptMessage {
        content = content == null ? "" : content;
    }

    public static PromptMessage system(String content) {
        return new PromptMessage(PromptMessageRole.SYSTEM, content);
    }

    public static PromptMessage user(String content) {
        return new PromptMessage(PromptMessageRole.USER, content);
    }

    public static PromptMessage assistant(String content) {
        return new PromptMessage(PromptMessageRole.ASSISTANT, content);
    }
}

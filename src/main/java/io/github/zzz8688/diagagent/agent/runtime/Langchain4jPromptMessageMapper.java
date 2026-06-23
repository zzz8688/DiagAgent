package io.github.zzz8688.diagagent.agent.runtime;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class Langchain4jPromptMessageMapper {

    public List<ChatMessage> toFrameworkMessages(PromptEnvelope promptEnvelope) {
        if (promptEnvelope == null || promptEnvelope.messages().isEmpty()) {
            return List.of();
        }
        return promptEnvelope.messages().stream()
                .map(this::toFrameworkMessage)
                .toList();
    }

    private ChatMessage toFrameworkMessage(PromptMessage message) {
        if (message == null || message.role() == null) {
            return UserMessage.from("");
        }
        return switch (message.role()) {
            case SYSTEM -> SystemMessage.from(message.content());
            case USER -> UserMessage.from(message.content());
            case ASSISTANT -> AiMessage.from(message.content());
        };
    }
}

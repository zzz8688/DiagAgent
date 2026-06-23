package io.github.zzz8688.diagagent.agent.runtime;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CriticModelGateway {

    @Qualifier("criticChatLanguageModel")
    private final ChatLanguageModel criticChatLanguageModel;
    private final Langchain4jPromptMessageMapper promptMessageMapper;

    public String complete(PromptEnvelope promptEnvelope) {
        List<ChatMessage> messages = promptMessageMapper.toFrameworkMessages(promptEnvelope);
        ChatResponse response = criticChatLanguageModel.chat(messages);
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            return "";
        }
        return response.aiMessage().text();
    }
}

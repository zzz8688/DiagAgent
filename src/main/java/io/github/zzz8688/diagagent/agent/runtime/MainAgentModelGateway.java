package io.github.zzz8688.diagagent.agent.runtime;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.zzz8688.diagagent.config.ModelConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MainAgentModelGateway {

    private final ChatLanguageModel chatLanguageModel;
    private final Langchain4jPromptMessageMapper promptMessageMapper;
    private final ModelConfig modelConfig;

    public String complete(List<ChatMessage> messages) {
        return completeResult(messages).rawText();
    }

    public Flux<String> stream(List<ChatMessage> messages) {
        return Flux.defer(() -> Flux.just(complete(messages)));
    }

    public String complete(PromptEnvelope promptEnvelope) {
        return complete(promptMessageMapper.toFrameworkMessages(promptEnvelope));
    }

    public Flux<String> stream(PromptEnvelope promptEnvelope) {
        return stream(promptMessageMapper.toFrameworkMessages(promptEnvelope));
    }

    public String complete(MainAgentPromptAssembler.PromptBundle bundle) {
        return complete(bundle.toPromptEnvelope());
    }

    public Flux<String> stream(MainAgentPromptAssembler.PromptBundle bundle) {
        return stream(bundle.toPromptEnvelope());
    }

    public EngineInvocationResult completeResult(PromptEnvelope promptEnvelope) {
        return completeResult(promptMessageMapper.toFrameworkMessages(promptEnvelope));
    }

    public EngineInvocationResult completeResult(MainAgentPromptAssembler.PromptBundle bundle) {
        return completeResult(bundle.toPromptEnvelope());
    }

    public EngineInvocationResult completeResult(List<ChatMessage> messages) {
        ChatResponse response = chatLanguageModel.chat(messages);
        String rawText = response == null || response.aiMessage() == null || response.aiMessage().text() == null
                ? ""
                : response.aiMessage().text();
        EngineProviderProfile providerProfile = new EngineProviderProfile(
                EngineProviderType.DASHSCOPE,
                modelConfig.getChatModelName(),
                "langchain4j",
                Map.of("gateway", "main-agent")
        );
        return new EngineInvocationResult(
                rawText,
                "completed",
                providerProfile,
                Map.of(
                        "providerModel", modelConfig.getChatModelName(),
                        "sdkBoundary", "langchain4j",
                        "gateway", "main-agent"
                )
        );
    }
}

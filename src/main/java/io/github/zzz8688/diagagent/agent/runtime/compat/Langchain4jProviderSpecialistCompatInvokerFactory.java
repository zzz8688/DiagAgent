package io.github.zzz8688.diagagent.agent.runtime.compat;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import io.github.zzz8688.diagagent.agent.runtime.SpecialistCompatInvoker;
import io.github.zzz8688.diagagent.agent.runtime.SpecialistCompatInvokerFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class Langchain4jProviderSpecialistCompatInvokerFactory implements SpecialistCompatInvokerFactory {

    private final ChatLanguageModel chatLanguageModel;
    private final ChatMemoryProvider chatMemoryProvider;

    @Override
    public SpecialistCompatInvoker create(String agentId,
                                          String sessionId,
                                          String bindingMode,
                                          String systemPrompt,
                                          Object[] frameworkTools) {
        Langchain4jSpecialistCompatService compatService = AiServices.builder(Langchain4jSpecialistCompatService.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemory(chatMemoryProvider.get(sessionId + "-" + agentId))
                .tools(frameworkTools)
                .systemMessageProvider(memoryId -> systemPrompt)
                .build();
        return new InternalSpecialistCompatInvoker(bindingMode, compatService::diagnose);
    }

    private interface Langchain4jSpecialistCompatService {

        String diagnose(@UserMessage String userQuery);
    }

    private record InternalSpecialistCompatInvoker(String bindingMode,
                                                   Function<String, String> delegate)
            implements SpecialistCompatInvoker {

        @Override
        public String invoke(String userQuestion) {
            return delegate.apply(userQuestion);
        }
    }
}

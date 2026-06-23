package io.github.zzz8688.diagagent.agent.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
@RequiredArgsConstructor
public class MainAgentFinalAnswerService {

    private final MainAgentPromptAssembler mainAgentPromptAssembler;
    private final EngineAdapter engineAdapter;

    public String answer(String userQuestion,
                         String specialistResults,
                         MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        MainAgentPromptAssembler.RenderedPrompt renderedPrompt = mainAgentPromptAssembler.buildFinalAnswerPrompt(
                userQuestion,
                specialistResults,
                promptContext
        );
        EngineRequest request = EngineRequest.forEnvelope(
                promptContext.sessionId(),
                engineAdapter.adapterId(),
                "main-agent-final-answer",
                renderedPrompt.toPromptEnvelope(),
                promptContext.readOnly(),
                java.util.Map.of(
                        "promptMode", safe(promptContext.promptMode()),
                        "turnIndex", Integer.toString(promptContext.turnIndex())
                )
        );
        return engineAdapter.invoke(request).rawText();
    }

    public Flux<String> answerStream(String userQuestion,
                                     String specialistResults,
                                     MainAgentPromptAssembler.MainAgentPromptContext promptContext) {
        MainAgentPromptAssembler.RenderedPrompt renderedPrompt = mainAgentPromptAssembler.buildFinalAnswerPrompt(
                userQuestion,
                specialistResults,
                promptContext
        );
        EngineRequest request = EngineRequest.forEnvelope(
                promptContext.sessionId(),
                engineAdapter.adapterId(),
                "main-agent-final-answer-stream",
                renderedPrompt.toPromptEnvelope(),
                promptContext.readOnly(),
                java.util.Map.of(
                        "promptMode", safe(promptContext.promptMode()),
                        "turnIndex", Integer.toString(promptContext.turnIndex())
                )
        );
        return engineAdapter.stream(request);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

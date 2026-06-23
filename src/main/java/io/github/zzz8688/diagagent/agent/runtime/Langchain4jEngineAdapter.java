package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.config.ModelConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Primary
@RequiredArgsConstructor
public class Langchain4jEngineAdapter implements EngineAdapter {

    private final MainAgentModelGateway mainAgentModelGateway;
    private final ModelConfig modelConfig;

    @Override
    public String adapterId() {
        return "langchain4j";
    }

    @Override
    public EngineResponse invoke(EngineRequest request) {
        try {
            EngineInvocationResult invocationResult = request.promptEnvelope() != null
                    ? mainAgentModelGateway.completeResult(request.promptEnvelope())
                    : mainAgentModelGateway.completeResult(request.promptBundle());
            Map<String, String> metadata = new LinkedHashMap<>(invocationResult.providerMetadata());
            metadata.put("operationName", safe(request.operationName()));
            metadata.put("engineType", safe(request.engineType()));
            metadata.putAll(invocationResult.providerProfile().metadata());
            return EngineResponse.success(
                    adapterId(),
                    invocationResult.providerProfile().providerType(),
                    invocationResult.providerProfile().modelName(),
                    invocationResult.rawText(),
                    invocationResult.finishReason(),
                    metadata
            );
        } catch (Exception e) {
            return EngineResponse.failure(
                    adapterId(),
                    EngineProviderType.DASHSCOPE,
                    modelConfig.getChatModelName(),
                    EngineErrorNormalizer.codeFor(e),
                    EngineErrorNormalizer.messageFor(e),
                    Map.of(
                            "operationName", safe(request.operationName()),
                            "engineType", safe(request.engineType()),
                            "sdkBoundary", "langchain4j"
                    )
            );
        }
    }

    @Override
    public Flux<String> stream(EngineRequest request) {
        return request.promptEnvelope() != null
                ? mainAgentModelGateway.stream(request.promptEnvelope())
                : mainAgentModelGateway.stream(request.promptBundle());
    }

    @Override
    public EngineDescriptor describe() {
        return new EngineDescriptor(
                adapterId(),
                "LangChain4j Chat Adapter",
                EngineProviderType.DASHSCOPE,
                modelConfig.getChatModelName(),
                true,
                true,
                "Wraps MainAgentModelGateway and keeps langchain4j on the adapter boundary."
        );
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}

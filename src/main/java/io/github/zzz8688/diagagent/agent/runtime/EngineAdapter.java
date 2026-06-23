package io.github.zzz8688.diagagent.agent.runtime;

import reactor.core.publisher.Flux;

public interface EngineAdapter {

    String adapterId();

    EngineResponse invoke(EngineRequest request);

    Flux<String> stream(EngineRequest request);

    default void cancel(String taskId) {
        // No-op by default. Adapters with real server-side runs can override this.
    }

    EngineDescriptor describe();
}

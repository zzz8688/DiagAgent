package io.github.zzz8688.diagagent.agent.runtime;

import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import io.github.zzz8688.diagagent.service.ConcurrentSessionExecutionException;
import io.github.zzz8688.diagagent.service.RedisRuntimeCoordinationService;
import io.github.zzz8688.diagagent.service.StopGenerateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RuntimeFacade {

    private final QueryRuntime queryRuntime;
    private final StopGenerateService stopGenerateService;
    private final TaskManager taskManager;
    private final RedisRuntimeCoordinationService redisRuntimeCoordinationService;

    public DiagnosisConclusion execute(RuntimeRequest request) {
        String sessionId = request == null ? null : request.sessionId();
        String token = redisRuntimeCoordinationService.tryAcquireSessionExecutionLock(sessionId, "sync");
        if (token == null) {
            throw new ConcurrentSessionExecutionException(sessionId);
        }
        try {
            return queryRuntime.execute(request);
        } finally {
            redisRuntimeCoordinationService.releaseSessionExecutionLock(sessionId, token);
        }
    }

    public Flux<String> stream(RuntimeRequest request) {
        String sessionId = request == null ? null : request.sessionId();
        String token = redisRuntimeCoordinationService.tryAcquireSessionExecutionLock(sessionId, "stream");
        if (token == null) {
            return Flux.error(new ConcurrentSessionExecutionException(sessionId));
        }
        return queryRuntime.stream(request)
                .doFinally(signalType -> redisRuntimeCoordinationService.releaseSessionExecutionLock(sessionId, token));
    }

    public void cancel(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        stopGenerateService.cancelTask(sessionId);
        taskManager.cancelLatestBySessionId(sessionId, "cancel requested from runtime facade");
    }

    public Optional<RuntimeTask> getTask(String taskId) {
        return taskManager.findTask(taskId);
    }

    public List<ExecutionEvent> replay(String taskId) {
        return taskManager.replay(taskId);
    }
}

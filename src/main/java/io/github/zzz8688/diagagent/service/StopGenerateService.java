package io.github.zzz8688.diagagent.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class StopGenerateService {

    private static final Logger log = LoggerFactory.getLogger(StopGenerateService.class);

    private final RedisCacheService redisCacheService;
    private final TaskExecutionManager taskExecutionManager;

    public void startGenerate(String sessionId) {
        redisCacheService.startGenerate(sessionId);
        log.debug("Started generate for session: {}", sessionId);
    }

    public void stopGenerate(String sessionId) {
        stopGenerate(sessionId, false);
    }

    public void stopGenerate(String sessionId, boolean shouldInterrupt) {
        if (!redisCacheService.tryMarkStopSignal(sessionId, shouldInterrupt)) {
            log.info("Duplicate stop signal ignored for session: {}, interrupt={}", sessionId, shouldInterrupt);
            return;
        }
        redisCacheService.stopGenerate(sessionId);

        boolean stopped = taskExecutionManager.stopTask(sessionId, shouldInterrupt);
        log.info("Stop generate for session: {}, task was running: {}, interrupt={}", sessionId, stopped, shouldInterrupt);
    }

    public void cancelTask(String sessionId) {
        stopGenerate(sessionId, true);
    }

    public boolean isGenerating(String sessionId) {
        return redisCacheService.isGenerating(sessionId);
    }

    public boolean shouldContinue(String sessionId) {
        return redisCacheService.shouldContinue(sessionId);
    }

    public long getRemainingTime(String sessionId) {
        return redisCacheService.getRemainingTime(sessionId);
    }
}

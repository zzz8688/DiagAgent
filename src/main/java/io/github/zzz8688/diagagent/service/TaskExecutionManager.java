package io.github.zzz8688.diagagent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TaskExecutionManager {

    private final InterruptFlagManager interruptFlagManager;

    public boolean stopTask(String sessionId, boolean shouldInterrupt) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        if (shouldInterrupt) {
            interruptFlagManager.requestInterrupt(sessionId);
        }
        return true;
    }
}

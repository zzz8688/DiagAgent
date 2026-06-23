package io.github.zzz8688.diagagent.service;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InterruptFlagManager {

    private final Set<String> interruptedSessions = ConcurrentHashMap.newKeySet();

    public void requestInterrupt(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            interruptedSessions.add(sessionId);
        }
    }

    public boolean isInterruptRequested(String sessionId) {
        return sessionId != null && interruptedSessions.contains(sessionId);
    }

    public void clearInterrupt(String sessionId) {
        if (sessionId != null) {
            interruptedSessions.remove(sessionId);
        }
    }
}

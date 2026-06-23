package io.github.zzz8688.diagagent.service;

public class ConcurrentSessionExecutionException extends RuntimeException {

    private final String sessionId;

    public ConcurrentSessionExecutionException(String sessionId) {
        super("session is already executing: " + (sessionId == null ? "" : sessionId));
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}

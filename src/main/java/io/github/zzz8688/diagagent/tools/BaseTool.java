package io.github.zzz8688.diagagent.tools;

import io.github.zzz8688.diagagent.config.SessionContext;
import io.github.zzz8688.diagagent.config.SpringContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseTool {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected void beforeToolCall(String toolName, String toolInput) {
        checkCancelled();
        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            try {
                var loopGuardService = SpringContext.getBean(io.github.zzz8688.diagagent.service.ReactLoopGuardService.class);
                loopGuardService.onToolCall(sessionId, toolName, toolInput);
            } catch (io.github.zzz8688.diagagent.service.ReactLoopGuardService.BaseToolLoopException e) {
                throw new ToolExecutionCancelledException(e.getMessage());
            }
        }
        checkCancelled();
    }

    protected void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            log.warn("Thread interrupted, throwing exception to abort tool execution");
            Thread.interrupted();
            throw new ToolExecutionCancelledException("Tool execution cancelled");
        }

        String sessionId = SessionContext.getSessionId();
        if (sessionId != null) {
            try {
                var interruptFlagManager = SpringContext.getBean(io.github.zzz8688.diagagent.service.InterruptFlagManager.class);
                if (interruptFlagManager.isInterruptRequested(sessionId)) {
                    log.warn("Stop signal received for session: {}, aborting tool execution", sessionId);
                    interruptFlagManager.clearInterrupt(sessionId);
                    throw new ToolExecutionCancelledException("Stop signal received for session: " + sessionId);
                }
            } catch (Exception e) {
                if (e instanceof ToolExecutionCancelledException) {
                    throw e;
                }
            }
        }
    }

    public static class ToolExecutionCancelledException extends RuntimeException {
        public ToolExecutionCancelledException(String message) {
            super(message);
        }
    }
}

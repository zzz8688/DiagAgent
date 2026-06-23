package io.github.zzz8688.diagagent.config;

import io.github.zzz8688.diagagent.service.ConcurrentSessionExecutionException;
import org.apache.catalina.connector.ClientAbortException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbortException(ClientAbortException e) {
        log.debug("Client disconnected or request aborted: {}", e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public void handleIOException(IOException e) {
        String message = e.getMessage();
        if (message != null && (message.contains("The current thread was interrupted") || 
                               message.contains("Broken pipe") || 
                               message.contains("Connection reset"))) {
            log.debug("Client disconnected or request aborted: {}", message);
        } else {
            log.error("IO Error processing request", e);
        }
    }

    @ExceptionHandler(InterruptedException.class)
    public void handleInterruptedException(InterruptedException e) {
        log.debug("Thread was interrupted (likely user cancelled request): {}", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ResponseEntity<Map<String, String>> handleNoResourceFoundException(NoResourceFoundException e) {
        log.warn("Requested resource not found: {}", e.getResourcePath());
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Resource not found";
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", errorMessage));
    }

    @ExceptionHandler(ConcurrentSessionExecutionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ResponseEntity<Map<String, String>> handleConcurrentSessionExecutionException(ConcurrentSessionExecutionException e) {
        log.warn("Concurrent session execution rejected: sessionId={}", e.getSessionId());
        String errorMessage = e.getMessage() != null ? e.getMessage() : "session is already executing";
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "error", errorMessage,
                        "sessionId", e.getSessionId() == null ? "" : e.getSessionId()
                ));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("Error processing request", e);
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Internal server error";
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", errorMessage));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ResponseEntity<Map<String, String>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.error("Bad request", e);
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Bad request";
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", errorMessage));
    }
}

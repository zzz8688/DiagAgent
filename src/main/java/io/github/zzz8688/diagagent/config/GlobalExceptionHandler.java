package io.github.zzz8688.diagagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.Map;

@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

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

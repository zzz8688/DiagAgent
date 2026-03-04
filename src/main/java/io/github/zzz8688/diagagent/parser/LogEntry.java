package io.github.zzz8688.diagagent.parser;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LogEntry {
    private String rawLog;
    private LocalDateTime timestamp;
    private String serviceName;
    private String traceId;
    private String level;
    private String message;
    private String template; 
}

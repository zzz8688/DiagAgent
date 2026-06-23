package io.github.zzz8688.diagagent.parser;

import org.springframework.stereotype.Service;

@Service
public class LogParserService {

    public LogEntry parse(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return new LogEntry("", "INFO", "unknown", "");
        }
        String[] parts = rawLog.trim().split("\\s+", 4);
        String timestamp = parts.length > 0 ? parts[0] : "";
        String level = parts.length > 1 ? parts[1] : "INFO";
        String service = parts.length > 2 ? parts[2] : "unknown";
        String message = parts.length > 3 ? parts[3] : rawLog;
        return new LogEntry(timestamp, level, service, message);
    }
}

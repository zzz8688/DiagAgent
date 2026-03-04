package io.github.zzz8688.diagagent.parser;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class LogParserService {

    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(\\w+)\\s+\\[([\\w-]+),([\\w]+)\\]\\s+(.*)$"
    );

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static final Pattern UUID_PATTERN = Pattern.compile("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    private static final Pattern COMPACT_UUID_PATTERN = Pattern.compile("[a-f0-9]{32}"); 
    private static final Pattern IP_PATTERN = Pattern.compile("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern USER_ID_PATTERN = Pattern.compile("user_\\d+");

    public LogEntry parse(String rawLog) {
        Matcher matcher = LOG_PATTERN.matcher(rawLog);
        if (matcher.find()) {
            String timestampStr = matcher.group(1);
            String level = matcher.group(2);
            String serviceName = matcher.group(3);
            String traceId = matcher.group(4);
            String message = matcher.group(5);

            LocalDateTime timestamp = LocalDateTime.parse(timestampStr, DATE_FORMATTER);
            String template = extractTemplate(message);

            return LogEntry.builder()
                    .rawLog(rawLog)
                    .timestamp(timestamp)
                    .serviceName(serviceName)
                    .traceId(traceId)
                    .level(level)
                    .message(message)
                    .template(template)
                    .build();
        }
        return null; 
    }

    private String extractTemplate(String message) {
        String template = message;

        template = USER_ID_PATTERN.matcher(template).replaceAll("<USER_ID>");
        template = UUID_PATTERN.matcher(template).replaceAll("<UUID>");
        template = COMPACT_UUID_PATTERN.matcher(template).replaceAll("<UUID>");
        template = IP_PATTERN.matcher(template).replaceAll("<IP>");

        template = NUMBER_PATTERN.matcher(template).replaceAll("<NUM>");

        return template;
    }
}

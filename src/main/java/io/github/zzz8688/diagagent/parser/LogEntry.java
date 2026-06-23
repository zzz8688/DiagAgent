package io.github.zzz8688.diagagent.parser;

public class LogEntry {

    private String timestamp;
    private String level;
    private String service;
    private String message;

    public LogEntry() {
    }

    public LogEntry(String timestamp, String level, String service, String message) {
        this.timestamp = timestamp;
        this.level = level;
        this.service = service;
        this.message = message;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

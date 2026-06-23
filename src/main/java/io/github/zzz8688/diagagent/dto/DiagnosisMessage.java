package io.github.zzz8688.diagagent.dto;

public class DiagnosisMessage {

    private String taskId;
    private String userQuery;
    private String sessionId;
    private boolean readOnly;

    public DiagnosisMessage() {
    }

    public DiagnosisMessage(String taskId, String userQuery, String sessionId, boolean readOnly) {
        this.taskId = taskId;
        this.userQuery = userQuery;
        this.sessionId = sessionId;
        this.readOnly = readOnly;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }
}

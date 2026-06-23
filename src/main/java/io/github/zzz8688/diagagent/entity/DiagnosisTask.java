package io.github.zzz8688.diagagent.entity;

import io.github.zzz8688.diagagent.dto.DiagnosisConclusion;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "diagnosis_tasks")
public class DiagnosisTask {

    @Id
    private String id;

    private String userQuery;

    private String sessionId;

    private DiagnosisTaskStatus status;

    private DiagnosisConclusion result;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public DiagnosisTask() {
    }

    public DiagnosisTask(String id, String userQuery, String sessionId, DiagnosisTaskStatus status,
                         DiagnosisConclusion result, String errorMessage, LocalDateTime createdAt,
                         LocalDateTime updatedAt) {
        this.id = id;
        this.userQuery = userQuery;
        this.sessionId = sessionId;
        this.status = status;
        this.result = result;
        this.errorMessage = errorMessage;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public DiagnosisTaskStatus getStatus() {
        return status;
    }

    public void setStatus(DiagnosisTaskStatus status) {
        this.status = status;
    }

    public DiagnosisConclusion getResult() {
        return result;
    }

    public void setResult(DiagnosisConclusion result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

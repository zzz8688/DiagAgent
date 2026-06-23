package io.github.zzz8688.diagagent.entity;

import java.time.LocalDateTime;

public class DiagnosisSession {

    private Long id;

    private String sessionId;

    private String title;

    private String engine;

    private Long userId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public DiagnosisSession() {
    }

    public DiagnosisSession(Long id, String sessionId, String title, String engine, Long userId,
                            LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.title = title;
        this.engine = engine;
        this.userId = userId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public static final class Builder {
        private Long id;
        private String sessionId;
        private String title;
        private String engine;
        private Long userId;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder engine(String engine) {
            this.engine = engine;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
            return this;
        }

        public Builder updatedAt(LocalDateTime updatedAt) {
            this.updatedAt = updatedAt;
            return this;
        }

        public DiagnosisSession build() {
            return new DiagnosisSession(id, sessionId, title, engine, userId, createdAt, updatedAt);
        }
    }
}

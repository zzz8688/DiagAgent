package io.github.zzz8688.diagagent.entity;

import java.time.LocalDateTime;

public class DiagnosisRecord {

    private Long id;

    private String query;

    private String conclusion;

    private String rootCause;

    private String suggestions;

    private Double confidence;

    private String engine;

    private Boolean verified;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public DiagnosisRecord() {
    }

    public DiagnosisRecord(Long id, String query, String conclusion, String rootCause, String suggestions,
                           Double confidence, String engine, Boolean verified, LocalDateTime createdAt,
                           LocalDateTime updatedAt) {
        this.id = id;
        this.query = query;
        this.conclusion = conclusion;
        this.rootCause = rootCause;
        this.suggestions = suggestions;
        this.confidence = confidence;
        this.engine = engine;
        this.verified = verified;
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

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getConclusion() {
        return conclusion;
    }

    public void setConclusion(String conclusion) {
        this.conclusion = conclusion;
    }

    public String getRootCause() {
        return rootCause;
    }

    public void setRootCause(String rootCause) {
        this.rootCause = rootCause;
    }

    public String getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(String suggestions) {
        this.suggestions = suggestions;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getEngine() {
        return engine;
    }

    public void setEngine(String engine) {
        this.engine = engine;
    }

    public Boolean getVerified() {
        return verified;
    }

    public void setVerified(Boolean verified) {
        this.verified = verified;
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
        private String query;
        private String conclusion;
        private String rootCause;
        private String suggestions;
        private Double confidence;
        private String engine;
        private Boolean verified;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        private Builder() {
        }

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public Builder conclusion(String conclusion) {
            this.conclusion = conclusion;
            return this;
        }

        public Builder rootCause(String rootCause) {
            this.rootCause = rootCause;
            return this;
        }

        public Builder suggestions(String suggestions) {
            this.suggestions = suggestions;
            return this;
        }

        public Builder confidence(Double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder engine(String engine) {
            this.engine = engine;
            return this;
        }

        public Builder verified(Boolean verified) {
            this.verified = verified;
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

        public DiagnosisRecord build() {
            return new DiagnosisRecord(id, query, conclusion, rootCause, suggestions, confidence, engine, verified,
                    createdAt, updatedAt);
        }
    }
}
